/*
 * Copyright © 2014 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.cdap.security.server;

import org.apache.http.client.methods.HttpPost;
import org.apache.knox.gateway.services.security.token.impl.JWT;
import org.apache.knox.gateway.services.security.token.impl.JWTToken;
import org.apache.knox.gateway.util.CertificateUtils;
import org.apache.shiro.authz.UnauthorizedException;

import co.cask.cdap.common.conf.CConfiguration;
import co.cask.cdap.common.conf.Constants;
import co.cask.cdap.common.io.Codec;
import co.cask.cdap.security.auth.AccessToken;
import co.cask.cdap.security.auth.AccessTokenIdentifier;
import co.cask.cdap.security.auth.TokenManager;
import com.google.common.base.Charsets;
import com.google.common.base.Strings;
import com.google.gson.JsonObject;
import com.google.inject.Inject;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.RSASSAVerifier;

import io.netty.handler.codec.http.HttpHeaderNames;
import org.apache.commons.codec.binary.Base64;

import org.apache.http.NameValuePair;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.message.BasicNameValuePair;

import org.eclipse.jetty.http.HttpHeaders;
import org.eclipse.jetty.util.B64Code;
import org.eclipse.jetty.util.StringUtil;
import org.json.JSONObject;
import org.json.XML;
import org.keycloak.OAuth2Constants;
import org.keycloak.adapters.KeycloakDeployment;
import org.keycloak.adapters.KeycloakDeploymentBuilder;
import org.keycloak.adapters.rotation.AdapterTokenVerifier;
import org.keycloak.authorization.client.AuthzClient;
import org.keycloak.authorization.client.util.Http;
import org.keycloak.authorization.client.util.HttpResponseException;
import org.keycloak.common.VerificationException;
import org.keycloak.representations.AccessTokenResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;
import java.util.LinkedList;
import java.text.ParseException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;

/**
 * Generate and grant access token to authorized users.
 */
@Path("/")
public class GrantAccessToken {
  private static final Logger LOG = LoggerFactory.getLogger(GrantAccessToken.class);
  private final TokenManager tokenManager;
  private final Codec<AccessToken> tokenCodec;
  private final long tokenExpiration;
  private final long extendedTokenExpiration;
  private static CConfiguration conf;
  private static KeycloakDeployment deployment;
  private static boolean keycloakflag = false;

  /**
   * Create a new GrantAccessToken object to generate tokens for authorized users.
   */
  @Inject
  public GrantAccessToken(TokenManager tokenManager,
                          Codec<AccessToken> tokenCodec,
                          CConfiguration cConf) {
    this.tokenManager = tokenManager;
    this.tokenCodec = tokenCodec;
    this.conf = cConf;
    this.tokenExpiration = cConf.getLong(Constants.Security.TOKEN_EXPIRATION);
    this.extendedTokenExpiration = cConf.getLong(Constants.Security.EXTENDED_TOKEN_EXPIRATION);
    this.deployment = createKeycloakDeployment(cConf.getResource("cdap-site.xml").getPath());
  }

  /**
   * Initialize the TokenManager.
   */
  public void init() {
    tokenManager.start();
  }

  /**
   * Stop the TokenManager.
   */
  public void destroy() {
    tokenManager.stop();
  }

  /**
   * Paths to get Access Tokens.
   */
  public static final class Paths {
    public static final String GET_TOKEN = "token";
    public static final String GET_TOKEN_FROM_KNOX = "knoxToken";
    public static final String GET_EXTENDED_TOKEN = "extendedtoken";
    public static final String GET_TOKEN_FROM_KEYCLOAK = "keycloakToken";
    public static final String GET_REFRESH_TOKEN = "refreshToken";
    public static final String LOGOUT_END_POINT = "logout";
  }

  /**
   *  Get an AccessToken from KeycloakToken.
   */

    @Path(Paths.GET_TOKEN_FROM_KEYCLOAK)
    @GET
    @Produces("application/json")
    public Response tokenFromKeycloak(@Context HttpServletRequest request, @Context HttpServletResponse response)
            throws IOException, ServletException {
        try {
            AccessToken token = getTokenUsingKeycloak(request, response);
            if (token != null)
                return Response.status(200).build();
        } catch (Exception ex) {
            LOG.debug(ex.getMessage());
        }
        return Response.status(401).build();
    }

    @Path(Paths.GET_REFRESH_TOKEN)
    @POST
    @Produces("application/json")
    public Response refreshtokenFromKeycloak(@Context HttpServletRequest request, @Context HttpServletResponse response)
            throws HttpResponseException, VerificationException {

        List<String> userGroups = Collections.emptyList();

        long issueTime = System.currentTimeMillis();

        String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        String auth=null;

        if (authorizationHeader!=null && !Strings.isNullOrEmpty(authorizationHeader) && (authorizationHeader.trim().toLowerCase().startsWith("bearer "))) {
            auth = authorizationHeader.substring(7);
        }

        if (auth != null) {
            try {
                String client_id = deployment.getResourceName();
                String client_secret =  deployment.getResourceCredentials().get(client_id).toString();
                String refresh_token = request.getParameter(OAuth2Constants.REFRESH_TOKEN);
                String refreshUrl = deployment.getTokenUrl();

                Http http = new Http(new org.keycloak.authorization.client.Configuration(), (params, headers) -> {
                });
                AccessTokenResponse acesstokenresponse = null;
                acesstokenresponse = http.<AccessTokenResponse>post(refreshUrl)
                        .authentication()
                        .client()
                        .form()
                        .param(OAuth2Constants.GRANT_TYPE, OAuth2Constants.REFRESH_TOKEN)
                        .param(OAuth2Constants.REFRESH_TOKEN, refresh_token)
                        .param(OAuth2Constants.CLIENT_ID, client_id)
                        .param(OAuth2Constants.CLIENT_SECRET, client_secret)
                        .response()
                        .json(AccessTokenResponse.class)
                        .execute();

                org.keycloak.representations.AccessToken keycloakToken = AdapterTokenVerifier.verifyToken(acesstokenresponse.getToken(), deployment);
                long expireDuration = keycloakToken.getExpiration() - keycloakToken.getIssuedAt();
                long expireTime = issueTime + expireDuration * 1000;
                String refreshToken = acesstokenresponse.getRefreshToken();
                String username = keycloakToken.getPreferredUsername();
                AccessTokenIdentifier tokenIdentifier = new AccessTokenIdentifier(username, userGroups, issueTime, expireTime, acesstokenresponse.getToken());
                AccessToken cdapToken = tokenManager.signIdentifier(tokenIdentifier);
                setResponse(request, response, cdapToken, refreshToken, 1000 * expireDuration);
            } catch (HttpResponseException ex) {
                LOG.debug(ex.getMessage());
                return Response.status(ex.getStatusCode()).build();
            } catch (VerificationException e) {
                LOG.debug("Authorization header missing/invalid");
            } catch (Exception ex) {
                LOG.debug("Exception Occured while getting refresh token " + ex.getMessage());
            }
        }
        return Response.status(401).build();
    }

    @Path(Paths.LOGOUT_END_POINT)
    @POST
    @Produces("application/json")
    public Response logout(@Context HttpServletRequest request, @Context HttpServletResponse response)
            throws IOException, ServletException, VerificationException {
        try {
            String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
            String auth=null;

            if (authorizationHeader!=null && !Strings.isNullOrEmpty(authorizationHeader) && (authorizationHeader.trim().toLowerCase().startsWith("bearer "))) {
                auth = authorizationHeader.substring(7);
            }
            if (auth != null) {
                byte[] decodedToken = Base64.decodeBase64(auth);
                AccessToken accessToken = tokenCodec.decode(decodedToken);

                if (accessToken.getIdentifier().getExpireTimestamp() < System.currentTimeMillis()) {
                    return Response.status(HttpServletResponse.SC_UNAUTHORIZED).build();
                }

                String client_id = deployment.getResourceName();
                String client_secret = deployment.getResourceCredentials().get(client_id).toString();
                String refresh_token = request.getParameter(OAuth2Constants.REFRESH_TOKEN);
//                String logoutUrl = "http://192.168.154.194:8180/auth/realms/dev/protocol/openid-connect/logout";
                String logoutUrl = "http://"+deployment.getAuthUrl().getHost()+":"+deployment.getAuthUrl().getPort()+deployment.getLogoutUrl().getPath();

                HttpPost post = new HttpPost(logoutUrl);
                List<NameValuePair> parameters = new LinkedList<>();
                parameters.add(new BasicNameValuePair(OAuth2Constants.CLIENT_ID, client_id));
                parameters.add(new BasicNameValuePair(OAuth2Constants.CLIENT_SECRET, client_secret));
                parameters.add(new BasicNameValuePair(OAuth2Constants.REFRESH_TOKEN, refresh_token));

                UrlEncodedFormEntity formEntity = new UrlEncodedFormEntity(parameters, Charsets.UTF_8);
                post.setEntity(formEntity);
                org.apache.http.HttpResponse httpResponse = HttpClientBuilder.create().build().execute(post);

                if (httpResponse.getStatusLine().getStatusCode() == 204) {
                    return Response.status(200).build();
                } else {
                    return Response.status(httpResponse.getStatusLine().getStatusCode()).build();
                }
            }
        } catch (HttpResponseException ex) {
            return Response.status(ex.getStatusCode()).build();
        } catch (Exception ex) {
            return Response.status(HttpServletResponse.SC_UNAUTHORIZED).build();
        }
        return Response.status(HttpServletResponse.SC_UNAUTHORIZED).build();
    }

    private void setResponse(HttpServletRequest request, HttpServletResponse response, AccessToken token, String refreshToken,
                             long tokenValidity) throws IOException, ServletException {

        /* TO BE DONE */
        JsonObject json = new JsonObject();
        byte[] encodedIdentifier = Base64.encodeBase64(tokenCodec.encode(token));
        json.addProperty(ExternalAuthenticationServer.ResponseFields.ACCESS_TOKEN,
                new String(encodedIdentifier, Charsets.UTF_8));
        json.addProperty(ExternalAuthenticationServer.ResponseFields.TOKEN_TYPE,
                ExternalAuthenticationServer.ResponseFields.TOKEN_TYPE_BODY);
        json.addProperty(ExternalAuthenticationServer.ResponseFields.EXPIRES_IN,
                TimeUnit.SECONDS.convert(tokenValidity, TimeUnit.MILLISECONDS));

        if (refreshToken != null && !refreshToken.isEmpty()) {
            json.addProperty(OAuth2Constants.REFRESH_TOKEN, refreshToken);
        }

        response.getOutputStream().print(json.toString());
        response.setStatus(HttpServletResponse.SC_OK);
    }

    private AccessToken getTokenUsingKeycloak(HttpServletRequest request, HttpServletResponse response)
            throws IOException, ServletException, UnauthorizedException {

        /* TO BE DONE */

        String username;
        String refreshTokenString;
        org.keycloak.representations.AccessToken keycloakToken = null;
        boolean request_from_ui=false;
        List<String> userGroups = Collections.emptyList();
        String authorizationHeader = request.getHeader("keycloakToken");
        String wireToken;

        if(authorizationHeader==null){
            authorizationHeader = request.getAttribute("keycloakToken").toString();
            if(authorizationHeader!=null && !authorizationHeader.isEmpty())
                request_from_ui = true;
        }

        if (authorizationHeader != null && !Strings.isNullOrEmpty(authorizationHeader)) {
            wireToken = authorizationHeader;
        } else {
            wireToken = getJWTTokenFromCookie(request);
        }
        if (Strings.isNullOrEmpty(wireToken)) {
            LOG.debug("No valid 'Bearer Authorization' or 'Cookie' found in header, send 401");
            return null;
        }

        try {
            keycloakToken = AdapterTokenVerifier.verifyToken(wireToken, deployment);
            username = keycloakToken.getPreferredUsername();

        } catch (VerificationException e) {
            Response.status(401).build();
            throw new UnauthorizedException("Authorization header missing/invalid");
        }

        if (keycloakToken.isExpired()) {
            LOG.debug("token expiry date: " + new Date(keycloakToken.getExpiration()));
            Response.status(401).build();
            throw new UnauthorizedException("Token expired.");
        }

        long issueTime = (long) keycloakToken.getIssuedAt() * 1000;
        long expireTime = (long) keycloakToken.getExpiration() * 1000;

        AccessTokenIdentifier tokenIdentifier = new AccessTokenIdentifier(username, userGroups, issueTime, expireTime, wireToken);
        AccessToken cdapToken = tokenManager.signIdentifier(tokenIdentifier);
        LOG.debug("Issued token for user {}", username);

        if(!request_from_ui)
            setResponse(request, response, cdapToken, null, (expireTime - issueTime));

        return cdapToken;
    }


    /**
   * Get an AccessToken.
   */
  @Path(Paths.GET_TOKEN)
  @GET
  @Produces("application/json")
  public Response token(@Context HttpServletRequest request, @Context HttpServletResponse response)
      throws IOException, ServletException {
    grantToken(request, response, tokenExpiration);
    return Response.status(200).build();
  }

  /**
   * Get a long lasting Access Token.
   */
  @Path(Paths.GET_EXTENDED_TOKEN)
  @GET
  @Produces("application/json")
  public Response extendedToken(@Context HttpServletRequest request, @Context HttpServletResponse response)
    throws IOException, ServletException {
    grantToken(request, response, extendedTokenExpiration);
    return Response.status(200).build();
  }

  private AccessToken getTokenFromKNOX(HttpServletRequest request, HttpServletResponse response)
			throws IOException, ServletException {
		
		final String authorizationHeader = request.getHeader("knoxToken");
        	String wireToken = null;
        	long expireTime = -1l;
        	String username = null;
        	long issueTime = System.currentTimeMillis();

        	if (authorizationHeader!=null && !Strings.isNullOrEmpty(authorizationHeader)) {
            		wireToken = authorizationHeader;
        	} else {
            		wireToken = getJWTTokenFromCookie(request);
        	}
        	if (Strings.isNullOrEmpty(wireToken)) {
        		LOG.debug("No valid 'Bearer Authorization' or 'Cookie' found in header, send 401");
            		return null;
        	}

        	JWTToken token;
        	try {
            		token = new JWTToken(wireToken);
            		username = token.getSubject();
        	} catch (ParseException | NullPointerException e) {
            		e.printStackTrace();
            		throw new UnauthorizedException("Authorization header missing/invalid");
        	}

        	boolean validToken = verifyToken(token);
        	if(!validToken)
            		throw new UnauthorizedException("Not authorized");
        	
        	Date expires = token.getExpiresDate();
        	LOG.debug("token expiry date: " + expires.toString());
        	if (expires.before(new Date()))
            		throw new UnauthorizedException("Token expired.");
        
        	expireTime = Date.parse(expires.toString());
        
		List<String> userGroups = Collections.emptyList();

		AccessTokenIdentifier tokenIdentifier = new AccessTokenIdentifier(username, userGroups, issueTime, expireTime);
		AccessToken accessToken = tokenManager.signIdentifier(tokenIdentifier);
		LOG.debug("Issued token for user {}", username);
		return accessToken;
	} 
 
  private static boolean verifyToken(JWT token) {
        boolean rc = false;
        String verificationPem = conf.get(Constants.Security.KNOX_TOKEN_PUBLIC_KEY);
        LOG.info("key value : " + verificationPem);
        try {
            RSAPublicKey publicKey = CertificateUtils.parseRSAPublicKey(verificationPem);
            JWSVerifier verifier = new RSASSAVerifier(publicKey);
            rc = token.verify(verifier);
        } catch (Exception e) {
            if (LOG.isDebugEnabled()) {
                e.printStackTrace();
            }
            LOG.warn("Exception in verifying signature : ", e.toString());
            e.printStackTrace();
            return false;
        }
        return rc;
    }
	
	private static String getJWTTokenFromCookie(HttpServletRequest request) {
        String rawCookie = request.getHeader("cookie");
        if (rawCookie == null) {
        	return null;
        }
        String cookieToken = null;
        String cookieName = "hadoop-jwt";

        String[] rawCookieParams = rawCookie.split(";");
        for(String rawCookieNameAndValue :rawCookieParams) {
            String[] rawCookieNameAndValuePair = rawCookieNameAndValue.split("=");
            if ((rawCookieNameAndValuePair.length > 1) &&
                    (rawCookieNameAndValuePair[0].trim().equalsIgnoreCase(cookieName))) {
                cookieToken = rawCookieNameAndValuePair[1];
                break;
            }
        }
        return cookieToken;
    } 


  private void grantToken(HttpServletRequest request, HttpServletResponse response, long tokenValidity)
    throws IOException, ServletException {

      String username = null;
      String password = null;

      String credentials = request.getHeader(HttpHeaders.AUTHORIZATION);

      if (credentials != null) {
          int space = credentials.indexOf(' ');
          if (space > 0) {
              String method = credentials.substring(0, space);
              if ("basic".equalsIgnoreCase(method)) {
                  credentials = credentials.substring(space + 1);
                  credentials = B64Code.decode(credentials, StringUtil.__ISO_8859_1);
                  int i = credentials.indexOf(':');
                  if (i > 0) {
                      username = credentials.substring(0, i);
                      password = credentials.substring(i+1);
                      AuthzClient client = AuthzClient.create();
                      AccessTokenResponse keycloakResponse = client.obtainAccessToken(username,password);
                      if(keycloakResponse.getToken()!=null) {
                          // CREATE NEW CDAP TOKEN BASED ON KEYCLOAK ACCESS AND REFRESH TOKEN
                          request.setAttribute("keycloakToken",keycloakResponse.getToken());
                          AccessToken cdapToken = getTokenUsingKeycloak(request,response);
                          String refreshToken = keycloakResponse.getRefreshToken();
                          long expireDuration = keycloakResponse.getExpiresIn();
                          setResponse(request, response, cdapToken, refreshToken, 1000 * expireDuration);
                          return;
                      }
                  }
              }
          }
      }


/*

    String username = request.getUserPrincipal().getName();
    List<String> userGroups = Collections.emptyList();

    long issueTime = System.currentTimeMillis();
    long expireTime = issueTime + tokenValidity;
    // Create and sign a new AccessTokenIdentifier to generate the AccessToken.
    AccessTokenIdentifier tokenIdentifier = new AccessTokenIdentifier(username, userGroups, issueTime, expireTime);
    AccessToken token = tokenManager.signIdentifier(tokenIdentifier);
    LOG.debug("Issued token for user {}", username);

    // Set response headers
    response.setContentType("application/json;charset=UTF-8");
    response.addHeader(HttpHeaderNames.CACHE_CONTROL.toString(), "no-store");
    response.addHeader(HttpHeaderNames.PRAGMA.toString(), "no-cache");

    // Set response body
    JsonObject json = new JsonObject();
    byte[] encodedIdentifier = Base64.encodeBase64(tokenCodec.encode(token));
    json.addProperty(ExternalAuthenticationServer.ResponseFields.ACCESS_TOKEN,
                     new String(encodedIdentifier, Charsets.UTF_8));
    json.addProperty(ExternalAuthenticationServer.ResponseFields.TOKEN_TYPE,
                     ExternalAuthenticationServer.ResponseFields.TOKEN_TYPE_BODY);
    json.addProperty(ExternalAuthenticationServer.ResponseFields.EXPIRES_IN,
                     TimeUnit.SECONDS.convert(tokenValidity, TimeUnit.MILLISECONDS));

    response.getOutputStream().print(json.toString());
    response.setStatus(HttpServletResponse.SC_OK); */
  }

    public static KeycloakDeployment createKeycloakDeployment(String Configfile){

        try {
            File xmlFile = new File(Configfile);
            Reader fileReader = new FileReader(xmlFile);
            BufferedReader bufReader = new BufferedReader(fileReader);
            boolean flag = false;
            StringBuilder sb = new StringBuilder();
            String line = bufReader.readLine().trim();
            while (line != null) {
                if (line.endsWith("</keycloakConfiguration>")) {
                    flag = false;
                    break;
                }
                if (line.endsWith("<keycloakConfiguration>") || flag == true) {
                    if(flag)
                        sb.append(line).append("\n");
                    flag=true;
                }
                line = bufReader.readLine().trim();
            }

            if(sb.length()!=0) {
                String xml2String = sb.toString();

                JSONObject obj = XML.toJSONObject(xml2String);
                String str = obj.toString();
                InputStream is = new ByteArrayInputStream(str.getBytes());

                KeycloakDeployment deployment = KeycloakDeploymentBuilder.build(is);
                System.out.println(deployment.getRealm());
                return deployment;
            }
            else{
                throw new RuntimeException("Keycloak configuration is not defined");
            }

        }
        catch(Exception ex){
            throw new RuntimeException(ex.getMessage());
        }

    }
}
