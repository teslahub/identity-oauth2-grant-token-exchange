/*
 * Copyright (c) 2021, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License
 */

package org.wso2.carbon.identity.oauth2.grant.token.exchange;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.identity.application.authentication.framework.model.AuthenticatedUser;
import org.wso2.carbon.identity.application.common.model.ClaimMapping;
import org.wso2.carbon.identity.application.common.model.IdentityProvider;
import org.wso2.carbon.identity.core.util.IdentityUtil;
import org.wso2.carbon.identity.oauth.common.OAuth2ErrorCodes;
import org.wso2.carbon.identity.oauth.config.OAuthServerConfiguration;
import org.wso2.carbon.identity.oauth2.IdentityOAuth2Exception;
import org.wso2.carbon.identity.oauth2.dto.OAuth2AccessTokenRespDTO;
import org.wso2.carbon.identity.oauth2.grant.token.exchange.utils.TokenExchangeUtils;
import org.wso2.carbon.identity.oauth2.model.RequestParameter;
import org.wso2.carbon.identity.oauth2.token.OAuthTokenReqMessageContext;
import org.wso2.carbon.identity.oauth2.token.handlers.grant.AbstractAuthorizationGrantHandler;
import org.wso2.carbon.identity.oauth2.util.ClaimsUtil;
import org.wso2.carbon.identity.oauth2.util.OAuth2Util;
import org.wso2.carbon.utils.multitenancy.MultitenantConstants;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.wso2.carbon.identity.oauth2.grant.token.exchange.utils.TokenExchangeUtils.handleException;

/**
 * Class to handle Token Exchange grant type
 */
public class TokenExchangeGrantHandler extends AbstractAuthorizationGrantHandler {

    private static final Log log = LogFactory.getLog(TokenExchangeGrantHandler.class);
    private int validityPeriod;
    private boolean validateIAT = true;
    private String[] registeredClaimNames = new String[]{"iss", "sub", "aud", "exp", "nbf", "iat", "jti"};
    private String requested_token_type = TokenExchangeConstants.JWT_TOKEN_TYPE;

    @Override
    public void init() throws IdentityOAuth2Exception {
        super.init();

        /*
          From identity.xml following configs are read.

          <OAuth>
              <TokenExchangeGrant>
                  <EnableIATValidation>true</EnableIATValidation>
                  <IATValidityPeriod>30</IATValidityPeriod>
              </TokenExchangeGrant>
          </OAuth>
         */

        String validateIATProp = IdentityUtil.getProperty(TokenExchangeConstants.PROP_ENABLE_IAT_VALIDATION);
        if (StringUtils.isNotBlank(validateIATProp)) {
            validateIAT = Boolean.parseBoolean(validateIATProp);
        }

        String validityPeriodProp = IdentityUtil.getProperty(TokenExchangeConstants.PROP_IAT_VALIDITY_PERIOD);

        if (validateIAT) {
            if (StringUtils.isNotBlank(validityPeriodProp)) {
                try {
                    validityPeriod = Integer.parseInt(validityPeriodProp);
                } catch (NumberFormatException e) {
                    validityPeriod = TokenExchangeConstants.DEFAULT_IAT_VALIDITY_PERIOD;
                    log.warn("Invalid value: " + validityPeriodProp + " is set for IAT validity period. Using " +
                            "default value: " + validityPeriod + " minutes.");
                }
            } else {
                validityPeriod = TokenExchangeConstants.DEFAULT_IAT_VALIDITY_PERIOD;
                log.warn("Empty value is set for IAT validity period. Using default value: " + validityPeriod
                        + " minutes.");
            }
        }

        String registeredClaims = IdentityUtil.getProperty(TokenExchangeConstants.REGISTERED_CLAIMS);
        if (StringUtils.isNotBlank(registeredClaims)) {
            registeredClaimNames = registeredClaims.split("\\s*,\\s*");
        }

        if (log.isDebugEnabled()) {
            log.debug("Validate IAT is set to: " + validateIAT + " for JWT grant.");
            if (validateIAT) {
                log.debug("IAT validity period is set to: " + validityPeriod + " minutes for JWT grant.");
            }
        }
    }

    @Override
    public boolean validateGrant(OAuthTokenReqMessageContext tokReqMsgCtx) throws IdentityOAuth2Exception {
        SignedJWT signedJWT;
        IdentityProvider identityProvider;
        String tokenEndPointAlias;
        JWTClaimsSet claimsSet = null;
        String requested_audience = null;
        boolean audienceFound;

        RequestParameter[] params = tokReqMsgCtx.getOauth2AccessTokenReqDTO().getRequestParameters();
        Map<String, String[]> requestParams = Arrays.stream(params).collect(Collectors.toMap(RequestParameter::getKey,
                RequestParameter::getValue));
        if (requestParams.get(TokenExchangeConstants.REQUESTED_TOKEN_TYPE) != null) {
            requested_token_type = requestParams.get(TokenExchangeConstants.REQUESTED_TOKEN_TYPE)[0];
        }
        if(requestParams.get(TokenExchangeConstants.AUDIENCE) != null) {
            requested_audience = requestParams.get(TokenExchangeConstants.AUDIENCE)[0];
        }
        String tenantDomain = tokReqMsgCtx.getOauth2AccessTokenReqDTO().getTenantDomain();
        if (StringUtils.isEmpty(tenantDomain)) {
            tenantDomain = MultitenantConstants.SUPER_TENANT_DOMAIN_NAME;
        }
        if (TokenExchangeConstants.JWT_TOKEN_TYPE.equals(requested_token_type)) {
            signedJWT = TokenExchangeUtils.getSignedJWT(requestParams.get(TokenExchangeConstants.SUBJECT_TOKEN)[0]);
            if (signedJWT == null) {
                handleException(OAuth2ErrorCodes.INVALID_REQUEST, "No Valid subject token was found for "
                        + TokenExchangeConstants.TOKEN_EXCHANGE_GRANT_TYPE);
            } else {
                claimsSet = TokenExchangeUtils.getClaimSet(signedJWT);
            }

            if (claimsSet == null) {
                handleException(OAuth2ErrorCodes.INVALID_REQUEST, "Claim values are empty in the given JSON Web Token");
            }

            String jwtIssuer = claimsSet.getIssuer();
            String subject = resolveSubject(claimsSet);
            List<String> audiences = claimsSet.getAudience();
            Date expirationTime = claimsSet.getExpirationTime();
            Date notBeforeTime = claimsSet.getNotBeforeTime();
            Date issuedAtTime = claimsSet.getIssueTime();
            Map<String, Object> customClaims = new HashMap<>(claimsSet.getClaims());

            tokReqMsgCtx.addProperty(TokenExchangeConstants.EXPIRY_TIME, expirationTime);
            long currentTimeInMillis = System.currentTimeMillis();
            long timeStampSkewMillis = OAuthServerConfiguration.getInstance().getTimeStampSkewInSeconds() * 1000;

            if (StringUtils.isEmpty(jwtIssuer) || StringUtils.isEmpty(subject) || expirationTime == null ||
                    audiences == null) {
                handleException(OAuth2ErrorCodes.INVALID_REQUEST, "Mandatory fields(Issuer, Subject, Expiration time "
                        + "or Audience) are empty in the given JWT");
            }
            identityProvider = TokenExchangeUtils.getIdPByIssuer(jwtIssuer, tenantDomain);
            tokenEndPointAlias = TokenExchangeUtils.getTokenEndpointAlias(identityProvider, tenantDomain);
            try {
                if (signedJWT != null) {
                    if (TokenExchangeUtils.validateSignature(signedJWT, identityProvider, tenantDomain)) {
                        log.debug("Signature/MAC validated successfully.");
                    } else {
                        handleException(OAuth2ErrorCodes.INVALID_REQUEST, "Signature or Message Authentication " +
                                "invalid");
                    }
                }
            } catch (JOSEException e) {
                handleException(OAuth2ErrorCodes.INVALID_REQUEST, "Error when verifying signature");
            }
            TokenExchangeUtils.setAuthorizedUser(tokReqMsgCtx, identityProvider, subject);

            log.debug("Subject(sub) found in JWT: " + subject);
            log.debug(subject + " set as the Authorized User.");

            tokReqMsgCtx.setScope(tokReqMsgCtx.getOauth2AccessTokenReqDTO().getScope());
            if (StringUtils.isEmpty(tokenEndPointAlias)) {
                handleException(OAuth2ErrorCodes.INVALID_REQUEST, "Token Endpoint alias of the local Identity " +
                        "Provider has not been configured for " + identityProvider.getIdentityProviderName());
            }

            audienceFound = validateAudience(audiences, tokenEndPointAlias, requested_audience);
            if (!audienceFound) {
                handleException(TokenExchangeConstants.INVALID_TARGET, "Invalid audience values provided");
            }
            TokenExchangeUtils.checkExpirationTime(expirationTime, currentTimeInMillis, timeStampSkewMillis);
            if (notBeforeTime != null) {
                TokenExchangeUtils.checkNotBeforeTime(notBeforeTime, currentTimeInMillis, timeStampSkewMillis);
            } else {
                log.debug("Not Before Time(nbf) not found in JWT. Continuing Validation");
            }
            if (issuedAtTime == null) {
                log.debug("Issued At Time(iat) not found in JWT. Continuing Validation");
            } else if (!validateIAT) {
                log.debug("Issued At Time (iat) validation is disabled for the JWT");
            } else {
                TokenExchangeUtils.checkValidityOfTheToken(issuedAtTime, currentTimeInMillis, timeStampSkewMillis,
                        validityPeriod);
            }
            boolean customClaimsValidated = validateCustomClaims(customClaims);
            if (!customClaimsValidated) {
                handleException(OAuth2ErrorCodes.INVALID_REQUEST, "Custom Claims in the JWT were invalid");
            }
            log.debug("Subject JWT Token was validated successfully");
            if (OAuth2Util.isOIDCAuthzRequest(tokReqMsgCtx.getScope())) {
                TokenExchangeUtils.handleCustomClaims(tokReqMsgCtx, customClaims, identityProvider, tenantDomain,
                        registeredClaimNames);
            }
        } else {
            handleException(OAuth2ErrorCodes.INVALID_REQUEST, "Unsupported Requested Token Type : " +
                    requested_token_type + " provided");
        }
        return true;
    }

    @Override
    public OAuth2AccessTokenRespDTO issue(OAuthTokenReqMessageContext tokReqMsgCtx) throws IdentityOAuth2Exception {
        OAuth2AccessTokenRespDTO tokenRespDTO = super.issue(tokReqMsgCtx);
        AuthenticatedUser user = tokReqMsgCtx.getAuthorizedUser();
        Map<ClaimMapping, String> userAttributes = user.getUserAttributes();
        if (MapUtils.isNotEmpty(userAttributes)) {
            ClaimsUtil.addUserAttributesToCache(tokenRespDTO, tokReqMsgCtx, userAttributes);
        }
        tokenRespDTO.addParameter(TokenExchangeConstants.ISSUED_TOKEN_TYPE, requested_token_type);
        return tokenRespDTO;
    }

    @Override
    public boolean issueRefreshToken() throws IdentityOAuth2Exception {
        return OAuthServerConfiguration.getInstance()
                .getValueForIsRefreshTokenAllowed(TokenExchangeConstants.TOKEN_EXCHANGE_GRANT_TYPE);
    }

    /**
     * Method to validate the claims other than
     * iss - Issuer
     * sub - Subject
     * aud - Audience
     * exp - Expiration Time
     * nbf - Not Before
     * iat - Issued At
     * jti - JWT ID
     * typ - Type
     * in order to write your own way of validation,
     * you can extend this class and override this method
     *
     * @param customClaims a map of custom claims
     * @return whether the token is valid based on other claim values
     */
    protected boolean validateCustomClaims(Map<String, Object> customClaims) {

        return true;
    }

    protected boolean validateAudience(List<String> audiences, String tokenEndPointAlias, String requested_audience ) {
        return audiences != null && audiences.stream().anyMatch(aud -> aud.equals(tokenEndPointAlias));
    }

    protected String resolveSubject(JWTClaimsSet claimsSet) {
        return claimsSet.getSubject();
    }
}
