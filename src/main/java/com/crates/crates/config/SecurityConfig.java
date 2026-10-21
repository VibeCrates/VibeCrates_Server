package com.crates.crates.config;

import com.crates.crates.DTO.ApiResponse;
import com.crates.crates.jwt.JwtAuthenticationFilter;
import com.crates.crates.jwt.JwtTokenProvider;
import com.crates.crates.oauth.CustomOAuth2UserService;
import com.crates.crates.oauth.CustomOidcUserService;
import com.crates.crates.oauth.OAuth2AuthenticationSuccessHandler;
import com.crates.crates.user.CustomUserDetailsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final CustomUserDetailsService customUserDetailsService;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final CustomOidcUserService customOidcUserService;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()       // 로그인, 회원가입
                        .requestMatchers("/oauth2/**", "/login/**").permitAll() // OAuth2 리다이렉트
                        // 로드 밸런서 헬스 체크. 상태(UP/DOWN)만 나가고 세부 정보는 숨겨진다(show-details 기본 never).
                        // 외부에서는 CloudFront가 /actuator를 넘기지 않아 닿지 않는다.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        // oauth2Login이 같이 설정돼있으면 기본 AuthenticationEntryPoint가
                        // 브라우저용 로그인 페이지(HTML)를 응답으로 내려버림 -> REST API용으로 401 JSON 응답 강제
                        .authenticationEntryPoint((request, response, authException) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            response.getWriter().write(objectMapper.writeValueAsString(
                                    new ApiResponse<>(false, null, "인증이 필요합니다.")));
                        })
                )
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(endpoint -> endpoint
                                // userService는 일반 OAuth2 흐름에만 적용된다(카카오, 네이버).
                                .userService(customOAuth2UserService)
                                // 구글은 openid 스코프가 붙어 OIDC 흐름을 타므로 별도 등록이 필요하다.
                                // 이걸 빠뜨리면 스프링 기본 구현이 DefaultOidcUser를 만들어 성공 핸들러에서 캐스팅이 깨진다.
                                .oidcUserService(customOidcUserService))
                        .successHandler(oAuth2AuthenticationSuccessHandler)
                )
                .addFilterBefore(
                        new JwtAuthenticationFilter(jwtTokenProvider, customUserDetailsService),
                        UsernamePasswordAuthenticationFilter.class
                );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
