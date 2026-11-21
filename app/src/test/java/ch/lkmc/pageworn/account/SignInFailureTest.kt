package ch.lkmc.pageworn.account

import ch.lkmc.pageworn.server.ServerError
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SignInFailureTest {
    @Test fun eachLoginFailureHasItsOwnResult() {
        assertThat(signInFailure(ServerError.InvalidCredentials)).isEqualTo(SignInResult.InvalidCredentials)
        assertThat(signInFailure(ServerError.RateLimited(200))).isEqualTo(SignInResult.TooManyAttempts)
        assertThat(signInFailure(ServerError.Unreachable("refused"))).isEqualTo(SignInResult.AddressFailed(AddressProblem.Unreachable))
        assertThat(signInFailure(ServerError.Certificate("AA", ServerError.Certificate.Kind.CHANGED)))
            .isEqualTo(SignInResult.AddressFailed(AddressProblem.Certificate("AA", ServerError.Certificate.Kind.CHANGED)))
    }
}
