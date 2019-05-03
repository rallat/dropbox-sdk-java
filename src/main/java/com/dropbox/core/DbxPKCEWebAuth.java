package com.dropbox.core;

import com.dropbox.core.http.HttpRequestor;
import com.dropbox.core.util.LangUtil;
import com.dropbox.core.v2.DbxRawClientV2;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

import static com.dropbox.core.util.StringUtil.urlSafeBase64Encode;

/**
 *
 * <b>Beta</b>: This feature is not available to all developers. Please do NOT use it unless you are
 * early access partner of this feature. The function signature is subjected to change
 * in next minor version release.
 *
 * Does the OAuth 2 "authorization code" flow with Proof Key for Code Exchange(PKCE).
 *
 * PKCE allows "authorization code" flow without "client_secret". It enables "native
 * application", which is ensafe to hardcode client_secret in code, to use "authorization
 * code". If you application has a server, please use regular {@link DbxWebAuth} instead.
 *
 * PKCE is more secure than "token" flow. If authorization code is compromised during
 * transmission, it can't be used to exchange for access token without random generated
 * code_verifier, which is stored inside SDK.
 *
 * DbxPKCEWebAuth and {@link DbxWebAuth} has the same interface and slightly different behavior:
 * <ol>
 *     <li>The constructor should take {@link DbxAppInfo} without app secret.</li>
 *     <li>Two step of OAuth2: {@link #authorize(Request)} and
 *     {@link #finishFromRedirect(String, DbxSessionStore, Map)}, should be called on the same
 *     object.</li>
 * </ol>
 *
 * @see <a href="https://tools.ietf.org/html/rfc7636">https://tools.ietf.org/html/rfc7636</a>
 */
public class DbxPKCEWebAuth extends DbxWebAuth {
    private static final String CODE_VERIFIER_CHAR_SET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~";
    private static final int CODE_VERIFIER_SIZE = 128;
    private static final String CODE_CHALLENGE_METHODS = "S256";

    private String codeVerifier;

    /**
     * Creates a new instance that will perform the OAuth2 PKCE authorization flow using the given
     * OAuth request configuration.
     *
     * @param requestConfig HTTP request configuration, never {@code null}.
     * @param appInfo Your application's Dropbox API information (the app key), never {@code null}.
     *
     * @throws IllegalStateException if appInfo contains app secret.
     */
    public DbxPKCEWebAuth(DbxRequestConfig requestConfig, DbxAppInfo appInfo) {
        super(requestConfig, appInfo);
        this.codeVerifier = null;

        if (appInfo.hasSecret()) {
            throw new IllegalStateException("PKCE cdoe flow doesn't require app secret, if you " +
                    "decide to embed it in your app, please use regular DbxWebAuth instead.");
        }
    }

    private String generateCodeVerifier() {
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < CODE_VERIFIER_SIZE; i++) {
            sb.append(CODE_VERIFIER_CHAR_SET.charAt(RAND.nextInt(CODE_VERIFIER_CHAR_SET.length())));
        }

        return sb.toString();
    }

    String generateCodeChallenge(String codeVerifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] signiture = digest.digest(codeVerifier.getBytes("US-ASCII"));
            return urlSafeBase64Encode(signiture).replaceAll("=+$", ""); // remove trailing equal
        } catch (NoSuchAlgorithmException e) {
            throw LangUtil.mkAssert("Impossible", e);
        } catch (UnsupportedEncodingException e) {
            throw LangUtil.mkAssert("Impossible", e);
        }
    }

    /**
     * Starts authorization and returns an "authorization URL" on the Dropbox website that let
     * the user grant your app access to their Dropbox account.
     *
     * <p> If a redirect URI was specified ({@link Request.Builder#withRedirectUri}). The
     * redirect URI should bring user back to your app on end device. Call {@link
     * #finishFromRedirect} using the same {@link DbxPKCEWebAuth} instance with the query
     * parameters received from the redirect.
     *
     * <p> If no redirect URI was specified ({@link Request.Builder#withNoRedirect}), then users who
     * grant access will be shown an "authorization code". The user must copy/paste the
     * authorization code back into your app, at which point you can call {@link
     * #finishFromCode(String)} with the same {@link DbxPKCEWebAuth} instance from to get an access
     * token.
     *
     * @param request OAuth 2.0 web-based authorization flow request configuration
     *
     * @return Authorization URL of website user can use to authorize your app.
     *
     */
    @Override
    public String authorize(Request request) {
        if (deprecatedRequest != null) {
            throw new IllegalStateException("Must create this instance using DbxWebAuth(DbxRequestConfig,DbxAppInfo) to call this method.");
        }

        this.codeVerifier = generateCodeVerifier();

        Map<String, String> pkceParams = new HashMap<String, String>();
        pkceParams.put("code_challenge", this.generateCodeChallenge(this.codeVerifier));
        pkceParams.put("code_challenge_method", CODE_CHALLENGE_METHODS);

        return authorizeImpl(request, pkceParams);
    }

    /**
     * Call this after the user has visited the authorizaton URL and copy/pasted the authorization
     * code that Dropbox gave them, with the same {@link DbxPKCEWebAuth} instance that generated
     * the authorization URL.
     *
     * @throws DbxException if the instance is not the same one used to generate authorization
     * URL, or if an error occurs communicating with Dropbox.
     * @see DbxWebAuth#finishFromCode(String)
     */
    @Override
    public DbxAuthFinish finishFromCode(String code) throws DbxException {
        return super.finishFromCode(code);
    }

    /**
     * Call this after the user has visited the authorizaton URL and Dropbox has redirected them
     * back to your native app, with the same {@link DbxPKCEWebAuth} instance that generated
     * the authorization URL.
     *
     * @throws BadRequestException If the redirect request is missing required query parameters,
     * contains duplicate parameters, or includes mutually exclusive parameters (e.g. {@code
     * "error"} and {@code "code"}).
     * @throws BadStateException If the CSRF token retrieved from {@code sessionStore} is {@code
     * null} or malformed.
     * @throws CsrfException If the CSRF token passed in {@code params} does not match the CSRF
     * token from {@code sessionStore}. This implies the redirect request may be forged.
     * @throws NotApprovedException If the user chose to deny the authorization request.
     * @throws ProviderException If an OAuth2 error response besides {@code "access_denied"} is
     * set.
     * @throws DbxException if the instance is not the same one used to generate authorization
     * URL, or if an error occurs communicating with Dropbox.
     */
    @Override
    public DbxAuthFinish finishFromRedirect(String redirectUri,
                                            DbxSessionStore sessionStore,
                                            Map<String, String[]> params)
        throws DbxException, BadRequestException, BadStateException, CsrfException,
        NotApprovedException, ProviderException {
        return super.finishFromRedirect(redirectUri, sessionStore, params);
    }

    @Override
    DbxAuthFinish finish(String code, String redirectUri, final String state) throws DbxException {
        if (code == null) throw new NullPointerException("code");
        if (this.codeVerifier == null) {
            throw new IllegalStateException("Must initialize the PKCE flow by calling authorize " +
                    "first.");
        }

        Map<String, String> params = new HashMap<String, String>();
        params.put("grant_type", "authorization_code");
        params.put("code", code);
        params.put("locale", requestConfig.getUserLocale());
        params.put("client_id", appInfo.getKey());
        params.put("code_verifier", codeVerifier);

        if (redirectUri != null) {
            params.put("redirect_uri", redirectUri);
        }

        DbxAuthFinish dbxAuthFinish = DbxRequestUtil.doPostNoAuth(
                requestConfig,
                DbxRawClientV2.USER_AGENT_ID,
                appInfo.getHost().getApi(),
                "oauth2/token",
                DbxRequestUtil.toParamsArray(params),
                null,
                new DbxRequestUtil.ResponseHandler<DbxAuthFinish>() {
                    @Override
                    public DbxAuthFinish handle(HttpRequestor.Response response) throws DbxException {
                        if (response.getStatusCode() != 200) {
                            throw DbxRequestUtil.unexpectedStatus(response);
                        }
                        return DbxRequestUtil.readJsonFromResponse(DbxAuthFinish.Reader, response)
                                .withUrlState(state);
                    }
                }
        );

        this.codeVerifier = null;
        return dbxAuthFinish;
    }

}