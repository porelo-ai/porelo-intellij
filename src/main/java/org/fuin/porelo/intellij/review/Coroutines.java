package org.fuin.porelo.intellij.review;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import kotlinx.coroutines.CoroutineScope;
import kotlinx.coroutines.CoroutineScopeKt;
import kotlinx.coroutines.CoroutineStart;
import kotlinx.coroutines.Dispatchers;
import kotlinx.coroutines.Job;
import kotlinx.coroutines.SupervisorKt;

/**
 * Kotlin's coroutines from Java: a suspending call runs in a scope of its own that ends when what
 * it belongs to is disposed.
 */
final class Coroutines {

    private Coroutines() {}

    /**
     * Runs a suspending call until it returns or the owner is disposed, whichever comes first.
     *
     * <p>The call is handed the continuation it is to resume, so it passes it on as the last
     * argument of the Kotlin function it calls, which suspends and resumes it.
     *
     * @param owner Ends the call when disposed.
     * @param call The suspending call.
     * @return The call's job.
     */
    static Job runUntilDisposed(
            Disposable owner, Function2<CoroutineScope, Continuation<? super Unit>, Object> call) {
        final CoroutineScope scope =
                CoroutineScopeKt.CoroutineScope(
                        SupervisorKt.SupervisorJob(null).plus(Dispatchers.getDefault()));
        final Disposable cancel = () -> CoroutineScopeKt.cancel(scope, null);
        // Registered before the launch, so an owner disposed meanwhile still cancels it.
        if (!Disposer.tryRegister(owner, cancel)) {
            CoroutineScopeKt.cancel(scope, null);
        }
        final Job job =
                BuildersKt.launch(
                        scope, EmptyCoroutineContext.INSTANCE, CoroutineStart.DEFAULT, call);
        // A call that ends before its owner leaves nothing registered with it.
        job.invokeOnCompletion(
                ignored -> {
                    Disposer.dispose(cancel);
                    return Unit.INSTANCE;
                });
        return job;
    }
}
