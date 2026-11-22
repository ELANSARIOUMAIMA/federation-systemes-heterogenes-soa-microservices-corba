
package ma.mobility.carshare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CarshareApplication {

    public static void main(String[] args) {
        SpringApplication.run(CarshareApplication.class, args);
    }
}
