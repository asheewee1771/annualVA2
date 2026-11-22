package sg.annualva.harness;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.MethodIntrospector;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import sg.annualva.web.ResourceNotFoundException;

/**
 * The harness's one way to run a scheduled job (ticket 67): every {@code @Scheduled} method of a singleton bean of the
 * running context, named {@code SimpleClassName.method}, run on the real bean, on the request's thread, as the scheduler
 * would. The scheduler is off in tests ({@code annualva.scheduling.enabled} is false, so nothing enables it), so the job
 * beans are there and nothing else calls them. A job that throws answers 500, as any failed request does. Imported by
 * {@link TestEndpoints} like the rest of the harness, never found by the application's component scan.
 */
@TestComponent
@RestController
public class ScheduledJobRuns implements SmartInitializingSingleton {

    private final ConfigurableApplicationContext context;
    /** Written once, before the server takes a request. */
    private volatile Map<String, Runnable> jobs = Map.of();

    ScheduledJobRuns(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /** Reads the jobs once, when every singleton exists. */
    @Override
    public void afterSingletonsInstantiated() {
        jobs = discovered();
    }

    /** The name of every scheduled job, sorted. */
    @GetMapping("/api/test/scheduled-jobs")
    public List<String> names() {
        return List.copyOf(jobs.keySet());
    }

    /** One run of one scheduled job, as the scheduler starts it. */
    @PostMapping("/api/test/scheduled-jobs/{name}")
    public Map<String, String> ran(@PathVariable String name) {
        run(name);
        return Map.of("ran", name);
    }

    /**
     * One run of the mail job. It has an endpoint of its own only to tell a pass that the relay fake stopped mid-call
     * ({@code stopNext}) from one that returned, which {@link #ran} would answer as a failed request.
     */
    @PostMapping("/api/test/jobs/mail")
    public Map<String, String> mail() {
        try {
            run("MailDispatchJob.run");
        } catch (CapturingMailRelay.DispatcherStopped stopped) {
            return Map.of("ran", "stopped");
        }
        return Map.of("ran", "mail");
    }

    private void run(String name) {
        Runnable job = jobs.get(name);
        if (job == null) {
            throw new ResourceNotFoundException();
        }
        job.run();
    }

    /** Looks at each singleton's type first, so only a bean with a scheduled method is fetched. */
    private Map<String, Runnable> discovered() {
        var beans = context.getBeanFactory();
        Map<String, Runnable> found = new TreeMap<>();
        for (String name : beans.getBeanNamesForType(Object.class, false, false)) {
            Class<?> type = beans.getType(name, false);
            if (type == null) {
                continue;
            }
            Class<?> userType = ClassUtils.getUserClass(type);
            Map<Method, Scheduled> scheduled = MethodIntrospector.selectMethods(userType,
                    (MethodIntrospector.MetadataLookup<Scheduled>) method ->
                            AnnotatedElementUtils.findMergedAnnotation(method, Scheduled.class));
            if (scheduled.isEmpty()) {
                continue;
            }
            Object bean = beans.getBean(name);
            for (Method method : scheduled.keySet()) {
                Method invocable = AopUtils.selectInvocableMethod(method, bean.getClass());
                ReflectionUtils.makeAccessible(invocable);
                found.put(userType.getSimpleName() + "." + method.getName(),
                        () -> ReflectionUtils.invokeMethod(invocable, bean));
            }
        }
        return Collections.unmodifiableMap(found);
    }
}
