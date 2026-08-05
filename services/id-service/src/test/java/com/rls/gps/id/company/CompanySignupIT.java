package com.rls.gps.id.company;

import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;
import com.rls.gps.common.web.GpsHeaders;
import com.rls.gps.id.support.AbstractIdServiceIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Company registration end to end: the restricted endpoint, the sret guard, credential generation
 * and persistence, against a real MySQL.
 */
class CompanySignupIT extends AbstractIdServiceIT {

    /** Bound to the public (gateway-facing) port. */
    @Autowired
    private TestRestTemplate publicRest;

    @Autowired
    private CompanyRepository companyRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final TestRestTemplate internalRest = new TestRestTemplate();

    @BeforeEach
    void clean() {
        companyRepository.deleteAll();
    }

    @Test
    void registersACompanyAndReturnsItsCredentialsOnce() {
        ResponseEntity<String> response = signup(SERVER_SECRET,
                Map.of("name", "Acme Logistics", "contactEmail", "ops@acme.example"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String body = response.getBody();
        String appKey = JsonPath.read(body, "$.appKey");
        String appSecret = JsonPath.read(body, "$.appSecret");

        assertThat(JsonPath.<String>read(body, "$.companyId")).isNotBlank();
        assertThat(appKey).startsWith("ak_");
        assertThat(appSecret).startsWith("as_");
        assertThat(JsonPath.<String>read(body, "$.status")).isEqualTo("ACTIVE");

        Company saved = companyRepository.findByAppKey(appKey).orElseThrow();
        assertThat(saved.getName()).isEqualTo("Acme Logistics");
        assertThat(saved.getContactEmail()).isEqualTo("ops@acme.example");
        assertThat(saved.getAppSecretHash()).isNotEqualTo(appSecret).startsWith("$2");
        assertThat(passwordEncoder.matches(appSecret, saved.getAppSecretHash())).isTrue();
    }

    @Test
    void rejectsSignupWithoutTheServerSecret() {
        ResponseEntity<String> response = signup(null, Map.of("name", "No Secret Inc"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("invalid_server_secret");
        assertThat(companyRepository.count()).isZero();
    }

    @Test
    void rejectsSignupWithAWrongServerSecret() {
        ResponseEntity<String> response = signup("wrong-secret", Map.of("name", "Wrong Secret Inc"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(companyRepository.count()).isZero();
    }

    @Test
    void rejectsADuplicateCompanyNameRegardlessOfCase() {
        assertThat(signup(SERVER_SECRET, Map.of("name", "Acme Logistics")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<String> duplicate = signup(SERVER_SECRET, Map.of("name", "acme logistics"));

        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(JsonPath.<String>read(duplicate.getBody(), "$.code")).isEqualTo("company_already_exists");
        assertThat(companyRepository.count()).isEqualTo(1);
    }

    @Test
    void rejectsAnInvalidBody() {
        ResponseEntity<String> response = signup(SERVER_SECRET,
                Map.of("name", "x", "contactEmail", "not-an-email"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonPath.<String>read(response.getBody(), "$.code")).isEqualTo("validation_failed");
        assertThat(JsonPath.<List<String>>read(response.getBody(), "$.errors")).hasSize(2);
        assertThat(companyRepository.count()).isZero();
    }

    @Test
    void everyGeneratedAppKeyIsDistinct() {
        String first = JsonPath.read(signup(SERVER_SECRET, Map.of("name", "One")).getBody(), "$.appKey");
        String second = JsonPath.read(signup(SERVER_SECRET, Map.of("name", "Two")).getBody(), "$.appKey");

        assertThat(first).isNotEqualTo(second);
        assertThat(companyRepository.count()).isEqualTo(2);
    }

    @Test
    void signupIsNotReachableOnTheGatewayFacingPort() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(GpsHeaders.SERVER_SECRET, SERVER_SECRET);

        ResponseEntity<String> response = publicRest.postForEntity("/api/v1/company/signup",
                new HttpEntity<>(Map.of("name", "Sneaky Inc"), headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(companyRepository.count()).isZero();
    }

    private ResponseEntity<String> signup(String serverSecret, Map<String, String> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (serverSecret != null) {
            headers.set(GpsHeaders.SERVER_SECRET, serverSecret);
        }
        return internalRest.postForEntity(internalUrl("/api/v1/company/signup"),
                new HttpEntity<>(body, headers), String.class);
    }
}
