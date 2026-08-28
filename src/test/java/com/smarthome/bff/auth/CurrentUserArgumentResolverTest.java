package com.smarthome.bff.auth;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CurrentUserArgumentResolverTest {

    private final CurrentUserArgumentResolver resolver = new CurrentUserArgumentResolver();

    // sample handler signatures the resolver inspects
    @SuppressWarnings("unused")
    void annotatedString(@CurrentUser String user) {
    }

    @SuppressWarnings("unused")
    void annotatedNonString(@CurrentUser Integer user) {
    }

    @SuppressWarnings("unused")
    void plainString(String user) {
    }

    private MethodParameter param(String method, Class<?>... types) throws NoSuchMethodException {
        return new MethodParameter(getClass().getDeclaredMethod(method, types), 0);
    }

    @Test
    void supportsOnlyAStringParameterAnnotatedWithCurrentUser() throws Exception {
        assertThat(resolver.supportsParameter(param("annotatedString", String.class))).isTrue();
        assertThat(resolver.supportsParameter(param("annotatedNonString", Integer.class))).isFalse();
        assertThat(resolver.supportsParameter(param("plainString", String.class))).isFalse();
    }

    @Test
    void resolvesTheUserFromTheRequestAttributeTheFilterSet() {
        NativeWebRequest request = mock(NativeWebRequest.class);
        when(request.getAttribute(JwtAuthFilter.USER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)).thenReturn("demo");

        Object resolved = resolver.resolveArgument(null, null, request, null);

        assertThat(resolved).isEqualTo("demo");
    }

    @Test
    void throwsWhenNoAuthenticatedUserIsOnTheRequest() {
        NativeWebRequest request = mock(NativeWebRequest.class);
        when(request.getAttribute(JwtAuthFilter.USER_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST)).thenReturn(null);

        assertThatThrownBy(() -> resolver.resolveArgument(null, null, request, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no authenticated user");
    }
}
