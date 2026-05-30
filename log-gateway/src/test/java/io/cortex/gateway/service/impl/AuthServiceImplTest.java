package io.cortex.gateway.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.cortex.gateway.constants.ErrorCodes;
import io.cortex.gateway.dto.response.TokenResponse;
import io.cortex.gateway.exception.ApplicationException;
import io.cortex.gateway.security.BootstrapUsersProperties;
import io.cortex.gateway.security.BootstrapUsersProperties.BootstrapUser;
import io.cortex.gateway.security.IssuedTokens;
import io.cortex.gateway.security.JwtIssuer;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for {@link AuthServiceImpl}: bootstrap hashing, login
 * success / failure paths, and refresh rotation.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    /** Real Argon2 encoder so the hash <-> match path is genuinely exercised. */
    private final PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    /** Stubbed JWT issuer; we assert it is called with the expected roles. */
    @Mock private JwtIssuer jwtIssuer;

    /** Service under test (constructed in {@link #setUp()}). */
    private AuthServiceImpl service;

    /** Wires the service with two bootstrap users and triggers the @PostConstruct hash. */
    @BeforeEach
    void setUp() {
        final BootstrapUsersProperties props = new BootstrapUsersProperties(List.of(
                new BootstrapUser("admin", "admin-pass", List.of("ADMIN", "USER")),
                new BootstrapUser("user", "user-pass", List.of("USER"))));
        this.service = new AuthServiceImpl(props, this.encoder, this.jwtIssuer);
        this.service.hashBootstrapUsers();
    }

    /** Valid credentials -> issuer called with the user's roles and a Bearer response. */
    @Test
    void loginReturnsTokenResponseOnValidCredentials() {
        when(this.jwtIssuer.issue(eq("admin"), any()))
                .thenReturn(new IssuedTokens("access-jwt", "refresh-jwt", OffsetDateTime.now()));

        final TokenResponse response = this.service.login("admin", "admin-pass");

        assertThat(response.accessToken()).isEqualTo("access-jwt");
        assertThat(response.refreshToken()).isEqualTo("refresh-jwt");
        assertThat(response.tokenType()).isEqualTo("Bearer");

        final ArgumentCaptor<Collection<String>> roleCaptor = ArgumentCaptor.captor();
        verify(this.jwtIssuer).issue(eq("admin"), roleCaptor.capture());
        assertThat(roleCaptor.getValue()).containsExactly("ADMIN", "USER");
    }

    /** Unknown username -> UNAUTHENTICATED, issuer never called. */
    @Test
    void loginRejectsUnknownUsername() {
        assertThatThrownBy(() -> this.service.login("ghost", "anything"))
                .isInstanceOf(ApplicationException.class)
                .extracting(ex -> ((ApplicationException) ex).getErrorCode())
                .isEqualTo(ErrorCodes.UNAUTHENTICATED);
        verify(this.jwtIssuer, never()).issue(any(), any());
    }

    /** Wrong password -> UNAUTHENTICATED, issuer never called. */
    @Test
    void loginRejectsWrongPassword() {
        assertThatThrownBy(() -> this.service.login("user", "wrong"))
                .isInstanceOf(ApplicationException.class)
                .extracting(ex -> ((ApplicationException) ex).getErrorCode())
                .isEqualTo(ErrorCodes.UNAUTHENTICATED);
        verify(this.jwtIssuer, never()).issue(any(), any());
    }

    /** Refresh -> issuer consumes the old token then issues a new pair. */
    @Test
    void refreshIssuesNewPairForExistingSubject() {
        when(this.jwtIssuer.consumeRefreshAndReturnSubject("old-refresh")).thenReturn("user");
        when(this.jwtIssuer.issue(eq("user"), any()))
                .thenReturn(new IssuedTokens("a2", "r2", OffsetDateTime.now()));

        final TokenResponse response = this.service.refresh("old-refresh");

        assertThat(response.accessToken()).isEqualTo("a2");
        assertThat(response.refreshToken()).isEqualTo("r2");
    }

    /** Refresh for a subject that no longer exists -> UNAUTHENTICATED. */
    @Test
    void refreshRejectsSubjectThatNoLongerExists() {
        when(this.jwtIssuer.consumeRefreshAndReturnSubject("old-refresh")).thenReturn("ghost");

        assertThatThrownBy(() -> this.service.refresh("old-refresh"))
                .isInstanceOf(ApplicationException.class)
                .extracting(ex -> ((ApplicationException) ex).getErrorCode())
                .isEqualTo(ErrorCodes.UNAUTHENTICATED);
        verify(this.jwtIssuer, never()).issue(any(), any());
    }
}
