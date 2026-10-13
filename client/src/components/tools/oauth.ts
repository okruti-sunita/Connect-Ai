import {toolsApi} from './tools-api';

const POLL_MS = 1500;
const TIMEOUT_MS = 10 * 60 * 1000;

/**
 * Opens an empty popup. Call this directly inside the click handler, before any await,
 * otherwise browsers block the window.
 */
export function openSignInPopup(): Window | null {
    const width = 520;
    const height = 720;
    const left = Math.max(0, window.screenX + (window.outerWidth - width) / 2);
    const top = Math.max(0, window.screenY + (window.outerHeight - height) / 2);
    const popup = window.open('', 'connectai-oauth', `popup=yes,width=${width},height=${height},left=${left},top=${top}`);
    if (popup) {
        popup.document.title = 'Connect AI sign-in';
        popup.document.body.innerHTML = '<p style="font-family:system-ui;padding:24px">Opening the sign-in page…</p>';
    }
    return popup;
}

export interface SignInResult {
    ok: boolean;
    message: string;
}

/**
 * Runs the OAuth sign-in for one saved connection in the given popup and resolves when the
 * backend reports the outcome. The backend stores the token; nothing secret passes through here.
 */
export async function signInWithPopup(serverId: string, popup: Window | null): Promise<SignInResult> {
    if (!popup || popup.closed) {
        return {ok: false, message: 'The sign-in window was blocked. Allow pop-ups for this site and try again.'};
    }
    let attemptId: string;
    try {
        const started = await toolsApi.startOAuth(serverId);
        attemptId = started.attemptId;
        let callbackReceived = false;
        const onMessage = (event: MessageEvent<unknown>) => {
            if (event.origin !== started.callbackOrigin || !event.data || typeof event.data !== 'object') return;
            const data = event.data as Record<string, unknown>;
            if (data.type === 'connect-ai-oauth' && data.attemptId === attemptId
                && (data.status === 'SUCCEEDED' || data.status === 'FAILED')) callbackReceived = true;
        };
        window.addEventListener('message', onMessage);
        popup.location.href = started.authorizationUrl;
        const startedAt = Date.now();
        let cancellationRequested = false;
        try {
            while (Date.now() - startedAt < TIMEOUT_MS) {
                if (!callbackReceived) await new Promise((resolve) => setTimeout(resolve, POLL_MS));
                callbackReceived = false;
                try {
                    const attempt = await toolsApi.oauthAttempt(attemptId);
                    if (attempt.status === 'SUCCEEDED' || attempt.status === 'FAILED') {
                        if (!popup.closed) popup.close();
                        return {ok: attempt.status === 'SUCCEEDED', message: attempt.message};
                    }
                } catch (error) {
                    return {ok: false, message: error instanceof Error ? error.message : 'Sign-in status is unavailable.'};
                }
                if (popup.closed) {
                    if (!cancellationRequested) {
                        cancellationRequested = true;
                        // If callback processing already began, cancellation is ignored and the true result is polled.
                        void toolsApi.cancelOAuthAttempt(attemptId).catch(() => undefined);
                    }
                }
            }
            if (!popup.closed) popup.close();
            return {ok: false, message: 'Sign-in timed out. Try again.'};
        } finally {
            window.removeEventListener('message', onMessage);
        }
    } catch (error) {
        popup.close();
        return {ok: false, message: error instanceof Error ? error.message : 'Sign-in could not be started.'};
    }
}
