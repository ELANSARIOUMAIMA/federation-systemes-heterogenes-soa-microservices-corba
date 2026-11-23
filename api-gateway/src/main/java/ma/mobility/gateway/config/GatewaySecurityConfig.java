package ma.mobility.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class GatewaySecurityConfig {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean UserDetailsService userDetailsService(@Value("${gateway.auth.username}") String username,
                                                 @Value("${gateway.auth.password}") String password,
                                                 PasswordEncoder encoder) {
        if (username.isBlank() || password.isBlank()) {
            throw new IllegalStateException("GATEWAY_USERNAME et GATEWAY_PASSWORD doivent être définis");
        }
        return new InMemoryUserDetailsManager(User.withUsername(username).password(encoder.encode(password))
                .roles("USER").build());
    }

    @Bean SecurityFilterChain gatewaySecurity(HttpSecurity http) throws Exception {
        return http.csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults()).build();
    }
}
