import {toolsApi} from './tools-api';

const POLL_MS = 1500;
const TIMEOUT_MS = 5 * 60 * 1000;
/** After the popup closes, keep checking briefly: the result may land just after it closed. */
const AFTER_CLOSE_GRACE_MS = 4000;

/**
 * Opens an empty popup. Call this directly inside the click handler, before any await,
 * otherwise browsers block the window.
 */
export function openSignInPopup(): Window | null {
    const width = 520;
    const height = 680;
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
        popup.location.href = started.authorizationUrl;
    } catch (error) {
        popup.close();
        return {ok: false, message: error instanceof Error ? error.message : 'Sign-in could not be started.'};
    }

    const startedAt = Date.now();
    let closedAt: number | null = null;
    while (Date.now() - startedAt < TIMEOUT_MS) {
        await new Promise((resolve) => setTimeout(resolve, POLL_MS));
        try {
            const attempt = await toolsApi.oauthAttempt(attemptId);
            if (attempt.status !== 'PENDING') {
                if (!popup.closed && attempt.status === 'FAILED') popup.close();
                return {ok: attempt.status === 'SUCCEEDED', message: attempt.message};
            }
        } catch (error) {
            return {ok: false, message: error instanceof Error ? error.message : 'Sign-in status is unavailable.'};
        }
        if (popup.closed) {
            closedAt ??= Date.now();
            if (Date.now() - closedAt > AFTER_CLOSE_GRACE_MS) {
                return {ok: false, message: 'The sign-in window was closed before access was granted.'};
            }
        }
    }
    if (!popup.closed) popup.close();
    return {ok: false, message: 'Sign-in timed out. Try again.'};
}
