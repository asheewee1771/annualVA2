package sg.annualva.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Entry point. Component scanning covers every module package under sg.annualva. */
@SpringBootApplication(scanBasePackages = "sg.annualva")
@ConfigurationPropertiesScan("sg.annualva")
public class AnnualVaApplication {

    public static void main(String[] args) {
        SpringApplication.run(AnnualVaApplication.class, args);
    }
}
