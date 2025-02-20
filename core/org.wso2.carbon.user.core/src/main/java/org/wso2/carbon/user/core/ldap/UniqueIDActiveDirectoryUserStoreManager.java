/*
 * Copyright (c) 2019, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.user.core.ldap;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.user.api.Properties;
import org.wso2.carbon.user.api.Property;
import org.wso2.carbon.user.api.RealmConfiguration;
import org.wso2.carbon.user.core.UserCoreConstants;
import org.wso2.carbon.user.core.UserRealm;
import org.wso2.carbon.user.core.UserStoreConfigConstants;
import org.wso2.carbon.user.core.UserStoreException;
import org.wso2.carbon.user.core.claim.ClaimManager;
import org.wso2.carbon.user.core.common.RoleContext;
import org.wso2.carbon.user.core.common.User;
import org.wso2.carbon.user.core.profile.ProfileConfigurationManager;
import org.wso2.carbon.user.core.util.JNDIUtil;
import org.wso2.carbon.utils.Secret;
import org.wso2.carbon.utils.UnsupportedSecretTypeException;

import javax.naming.Name;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.PartialResultException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InvalidAttributeIdentifierException;
import javax.naming.directory.InvalidAttributeValueException;
import javax.naming.directory.ModificationItem;
import javax.naming.directory.NoSuchAttributeException;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringTokenizer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class is responsible for manipulating Microsoft Active Directory(AD)and Active Directory
 * Light Directory Service (AD LDS)data. This class provides facility to add/delete/modify/view user
 * info in a directory server.
 */
public class UniqueIDActiveDirectoryUserStoreManager extends UniqueIDReadWriteLDAPUserStoreManager {

    private static Log logger = LogFactory.getLog(UniqueIDActiveDirectoryUserStoreManager.class);
    private boolean isADLDSRole = false;
    private boolean isSSLConnection = false;
    private String userAccountControl = "512";
    private String userAttributeSeparator = ",";
    private static final String MULTI_ATTRIBUTE_SEPARATOR = "MultiAttributeSeparator";
    private static final String MULTI_ATTRIBUTE_SEPARATOR_DESCRIPTION =
            "This is the separator for multiple claim " + "values";
    private static final List<Property> UNIQUE_ID_ACTIVE_DIRECTORY_UM_ADVANCED_PROPERTIES = new ArrayList<>();
    private static final String LDAPConnectionTimeout = "LDAPConnectionTimeout";
    private static final String LDAPConnectionTimeoutDescription = "LDAP Connection Timeout";
    private static final String BULK_IMPORT_SUPPORT = "BulkImportSupported";
    private static final String readTimeout = "ReadTimeout";
    private static final String readTimeoutDescription =
            "Configure this to define the read timeout for LDAP " + "operations";
    private static final String RETRY_ATTEMPTS = "RetryAttempts";
    private static final String LDAPBinaryAttributesDescription =
            "Configure this to define the LDAP binary attributes " + "seperated by a space. Ex:mpegVideo mySpecialKey";
    private static final String ACTIVE_DIRECTORY_DATE_TIME_FORMAT = "uuuuMMddHHmmss[,S][.S]X";

    // For AD's this value is 1500 by default, hence overriding the default value.
    protected static final int MEMBERSHIP_ATTRIBUTE_RANGE_VALUE = 1500;

    static {
        setAdvancedProperties();
    }

    public UniqueIDActiveDirectoryUserStoreManager() {

    }

    /**
     * @param realmConfig
     * @param properties
     * @param claimManager
     * @param profileManager
     * @param realm
     * @param tenantId
     * @throws UserStoreException
     */
    public UniqueIDActiveDirectoryUserStoreManager(RealmConfiguration realmConfig, Map<String, Object> properties,
            ClaimManager claimManager, ProfileConfigurationManager profileManager, UserRealm realm, Integer tenantId)
            throws UserStoreException {

        super(realmConfig, properties, claimManager, profileManager, realm, tenantId);
        checkRequiredUserStoreConfigurations();
    }

    /**
     * @param realmConfig
     * @param claimManager
     * @param profileManager
     * @throws UserStoreException
     */
    public UniqueIDActiveDirectoryUserStoreManager(RealmConfiguration realmConfig, ClaimManager claimManager,
            ProfileConfigurationManager profileManager) throws UserStoreException {

        super(realmConfig, claimManager, profileManager);
        checkRequiredUserStoreConfigurations();
    }

    @Override
    public void doAddUser(String userName, Object credential, String[] roleList, Map<String, String> claims,
            String profileName) throws UserStoreException {

        throw new UserStoreException("Operation is not supported.");
    }

    @Override
    public void doAddUser(String userName, Object credential, String[] roleList, Map<String, String> claims,
            String profileName, boolean requirePasswordChange) throws UserStoreException {

        throw new UserStoreException("Operation is not supported.");
    }

    @Override
    public User doAddUserWithID(String userName, Object credential, String[] roleList, Map<String, String> claims,
            String profileName, boolean requirePasswordChange) throws UserStoreException {

        String userID = getUniqueUserID();
        // Update location claim with new User ID.
        updateLocationClaimWithUserId(userID, claims);
        persistUser(userID, userName, credential, roleList, claims);

        if (isUserIdGeneratedByUserStore(userName, claims)) {
            return getUser(null, userName);
        }

        return getUser(userID, userName);
    }

    @Override
    protected boolean isUserIdGeneratedByUserStore(String userID, Map<String, String> userAttributes) {

        String immutableAttributesProperty = Optional.ofNullable(realmConfig
                .getUserStoreProperty(UserStoreConfigConstants.immutableAttributes)).orElse("");

        String[] immutableAttributes = StringUtils.split(immutableAttributesProperty, ",");
        String userIdProperty = realmConfig.getUserStoreProperty(LDAPConstants.USER_ID_ATTRIBUTE);

        return ArrayUtils.contains(immutableAttributes, userIdProperty);
    }

    /**
     * This method persists the user information in the user store.
     */
    @Override
    protected void persistUser(String userID, String userName, Object credential, String[] roleList,
            Map<String, String> claims) throws UserStoreException {

        boolean isUserBinded = false;

        // getting search base directory context
        DirContext dirContext = getSearchBaseDirectoryContext();

        // getting add user basic attributes
        BasicAttributes basicAttributes = getAddUserBasicAttributes(userName);

        if (!isADLDSRole) {
            // creating a disabled user account in AD DS
            BasicAttribute userAccountControl = new BasicAttribute(LDAPConstants.ACTIVE_DIRECTORY_USER_ACCOUNT_CONTROL);
            userAccountControl.add(LDAPConstants.ACTIVE_DIRECTORY_DISABLED_NORMAL_ACCOUNT);
            basicAttributes.put(userAccountControl);
        }

        // setting claims
        setUserClaims(claims, basicAttributes, userID, userName);

        Secret credentialObj;
        try {
            credentialObj = Secret.getSecret(credential);
        } catch (UnsupportedSecretTypeException e) {
            throw new UserStoreException("Unsupported credential type", e);
        }

        Name compoundName = null;
        try {
            NameParser ldapParser = dirContext.getNameParser("");
            compoundName = ldapParser.parse(LDAPConstants.CN + "=" + escapeSpecialCharactersForDN(userName));

            // Bind the user. A disabled user account with no password.
            dirContext.bind(compoundName, null, basicAttributes);
            isUserBinded = true;

            if (isUserIdGeneratedByUserStore(userID, claims)) {
                String userIdAttribute = realmConfig.getUserStoreProperty(LDAPConstants.USER_ID_ATTRIBUTE);
                Attributes userAttributes = dirContext.getAttributes(compoundName, new String[]{userIdAttribute});
                userID = resolveLdapAttributeValue(userAttributes.get(userIdAttribute).get());
            }

            // Update the user roles.
            doUpdateRoleListOfUserWithID(userID, null, roleList);

            // Reset the password and enable the account.
            if (!isSSLConnection) {
                logger.warn("Unsecured connection is being used. Enabling user account operation will fail");
            }

            ModificationItem[] mods = new ModificationItem[2];
            mods[0] = new ModificationItem(DirContext.REPLACE_ATTRIBUTE,
                    new BasicAttribute(LDAPConstants.ACTIVE_DIRECTORY_UNICODE_PASSWORD_ATTRIBUTE,
                            createUnicodePassword(credentialObj)));

            if (isADLDSRole) {
                mods[1] = new ModificationItem(DirContext.REPLACE_ATTRIBUTE,
                        new BasicAttribute(LDAPConstants.ACTIVE_DIRECTORY_MSDS_USER_ACCOUNT_DISSABLED, "FALSE"));
            } else {
                mods[1] = new ModificationItem(DirContext.REPLACE_ATTRIBUTE,
                        new BasicAttribute(LDAPConstants.ACTIVE_DIRECTORY_USER_ACCOUNT_CONTROL, userAccountControl));
            }
            dirContext.modifyAttributes(compoundName, mods);

        } catch (NamingException e) {
            String errorMessage = "Error while adding the user to the Active Directory for user : " + userName;
            if (isUserBinded) {
                try {
                    dirContext.unbind(compoundName);
                } catch (NamingException e1) {
                    errorMessage = "Error while accessing the Active Directory for user : " + userName;
                    throw new UserStoreException(errorMessage, e);
                }
                errorMessage = "Error while enabling the user account. Please check password policy at DC for user : "
                        + userName;
            }
            throw new UserStoreException(errorMessage, e);
        } finally {
            credentialObj.clear();
            JNDIUtil.closeContext(dirContext);
        }
    }

    /**
     * Sets the set of claims provided at adding users
     *
     * @param claims
     * @param basicAttributes
     * @param userID          user ID.
     * @param userName        user name.
     * @throws UserStoreException
     */
    protected void setUserClaims(Map<String, String> claims, BasicAttributes basicAttributes, String userID,
            String userName) throws UserStoreException {

        if (claims != null) {
            Map<String, String> attributeValueMap = new HashMap<>();

            for (Map.Entry<String, String> entry : claims.entrySet()) {
                // avoid attributes with empty values
                if (EMPTY_ATTRIBUTE_STRING.equals(entry.getValue())) {
                    continue;
                }
                // needs to get attribute name from claim mapping
                String claimURI = entry.getKey();

                // skipping profile configuration attribute
                if (claimURI.equals(UserCoreConstants.PROFILE_CONFIGURATION)) {
                    continue;
                }

                String attributeName;
                try {
                    attributeName = getClaimAtrribute(claimURI, userName, null);
                } catch (org.wso2.carbon.user.api.UserStoreException e) {
                    String errorMessage = "Error in obtaining claim mapping.";
                    throw new UserStoreException(errorMessage, e);
                }

                attributeValueMap.put(attributeName, entry.getValue());
            }

            // Add userID attribute.
            attributeValueMap.put(realmConfig.getUserStoreProperty(LDAPConstants.USER_ID_ATTRIBUTE), userID);
            processAttributesBeforeUpdateWithID(userID, attributeValueMap, null);

            attributeValueMap.forEach((attributeName, attributeValue) -> {
                BasicAttribute claim = new BasicAttribute(attributeName);
                if (attributeValue != null) {
                    String claimSeparator = realmConfig.getUserStoreProperty(MULTI_ATTRIBUTE_SEPARATOR);
                    if (claimSeparator != null && !claimSeparator.trim().isEmpty()) {
                        userAttributeSeparator = claimSeparator;
                    }
                    if (attributeValue.contains(userAttributeSeparator)) {
                        StringTokenizer st = new StringTokenizer(attributeValue, userAttributeSeparator);
                        while (st.hasMoreElements()) {
                            String newVal = st.nextElement().toString();
                            if (newVal != null && newVal.trim().length() > 0) {
                                claim.add(newVal.trim());
                            }
                        }
                    } else {
                        claim.add(attributeValue);
                    }
                } else {
                    claim.add(attributeValue);
                }
                if (logger.isDebugEnabled()) {
                    logger.debug("AttributeName: " + attributeName + " AttributeValue: " + attributeValue);
                }
                basicAttributes.put(claim);
            });
        }
    }

    @Override
    public List<User> getUserListOfLDAPRoleWithID(RoleContext context, String filter) throws UserStoreException {

        boolean debug = logger.isDebugEnabled();

        if (debug) {
            logger.debug("Getting user list of role: " + context.getRoleName() + " with filter: " + filter);
        }

        List<User> userList = new ArrayList<>();
        int givenMax, searchTime;

        try {
            givenMax = Integer.parseInt(realmConfig
                    .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_MAX_USER_LIST));
        } catch (NumberFormatException e) {
            if (debug) {
                logger.debug("Error occurred while reading user store property: "
                        + UserCoreConstants.RealmConfig.PROPERTY_MAX_USER_LIST + " : " + e);
            }
            givenMax = UserCoreConstants.MAX_USER_ROLE_LIST;
        }

        try {
            searchTime = Integer.parseInt(realmConfig
                    .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_MAX_SEARCH_TIME));
        } catch (NumberFormatException e) {
            if (debug) {
                logger.debug("Error occurred while reading user store property: "
                        + UserCoreConstants.RealmConfig.PROPERTY_MAX_SEARCH_TIME + " : " + e);
            }
            searchTime = UserCoreConstants.MAX_SEARCH_TIME;
        }

        DirContext dirContext = null;
        NamingEnumeration<SearchResult> answer = null;

        try {
            SearchControls searchCtls = new SearchControls();
            searchCtls.setSearchScope(SearchControls.SUBTREE_SCOPE);
            searchCtls.setTimeLimit(searchTime);
            searchCtls.setCountLimit(givenMax);

            String groupSearchBase = realmConfig.getUserStoreProperty(LDAPConstants.GROUP_SEARCH_BASE);
            String userListFilter = realmConfig.getUserStoreProperty(LDAPConstants.USER_NAME_LIST_FILTER);
            String memberOFAttribute = realmConfig.getUserStoreProperty(LDAPConstants.MEMBEROF_ATTRIBUTE);
            String groupNameAttribute = realmConfig.getUserStoreProperty(LDAPConstants.GROUP_NAME_ATTRIBUTE);
            userSearchBase = realmConfig.getUserStoreProperty(LDAPConstants.USER_SEARCH_BASE);
            String searchFilter = "(&" + userListFilter + "(" + memberOFAttribute + "=" + groupNameAttribute + "="
                    + escapeSpecialCharactersForFilter(context.getRoleName()) + "," + groupSearchBase + "))";
            String userNameProperty = realmConfig.getUserStoreProperty(LDAPConstants.USER_NAME_ATTRIBUTE);
            String displayNameAttribute = realmConfig.getUserStoreProperty(LDAPConstants.DISPLAY_NAME_ATTRIBUTE);
            String userIDAttribute = realmConfig.getUserStoreProperty(LDAPConstants.USER_ID_ATTRIBUTE);
            String returnedAtts[] = {userNameProperty, displayNameAttribute, userIDAttribute};
            searchCtls.setReturningAttributes(returnedAtts);

            SearchResult sr = null;
            dirContext = connectionSource.getContext();

            answer = dirContext.search(escapeDNForSearch(userSearchBase), searchFilter, searchCtls);

            String displayName = null;
            String userName = null;
            String id = null;
            User userObject = null;

            for (int index = 0; index < givenMax && answer.hasMore(); index++) {
                sr = answer.next();
                Attributes userAttributes = sr.getAttributes();
                if (userAttributes != null) {
                    Attribute userNameAttribute = userAttributes.get(userNameProperty);
                    if (userNameAttribute != null) {
                        userName = (String) userNameAttribute.get();
                        if (debug) {
                            logger.debug("UserName: " + userName);
                        }
                    }
                    if (StringUtils.isNotEmpty(displayNameAttribute)) {
                        Attribute displayAttribute = userAttributes.get(displayNameAttribute);
                        if (displayAttribute != null) {
                            displayName = (String) displayAttribute.get();
                        }
                        if (debug) {
                            logger.debug("DisplayName: " + displayName);
                        }
                    }
                    if (StringUtils.isNotEmpty(userIDAttribute)) {
                        Attribute userID = userAttributes.get(userIDAttribute);
                        if (userID != null) {
                            id = resolveLdapAttributeValue(userID.get());
                        }
                        if (logger.isDebugEnabled()) {
                            logger.debug("UserID: " + id);
                        }
                    }

                    String domainName = realmConfig
                            .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_DOMAIN_NAME);

                    userObject = getUser(id, userName);
                    userObject.setDisplayName(displayName);
                    userObject.setUserStoreDomain(domainName);
                    userObject.setTenantDomain(getTenantDomain(tenantId));

                    userList.add(userObject);
                    if (logger.isDebugEnabled()) {
                        logger.debug(userObject.getUsername() + " is added to the result list");
                    }
                }
            }

        } catch (PartialResultException e) {
            // Can be due to referrals in AD. so just ignore the error.
            String errorMessage = "Error in reading user information in the user store for filter : " + filter;
            if (isIgnorePartialResultException()) {
                if (debug) {
                    logger.debug(errorMessage, e);
                }
            } else {
                throw new UserStoreException(errorMessage, e);
            }
        } catch (NamingException e) {
            String errorMessage = "Error in reading user information in the user store for filter : " + filter;
            if (debug) {
                logger.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        } finally {
            JNDIUtil.closeNamingEnumeration(answer);
            JNDIUtil.closeContext(dirContext);
        }

        return userList;
    }

    @Override
    public void doUpdateCredential(String userName, Object newCredential, Object oldCredential)
            throws UserStoreException {

        throw new UserStoreException("Operation is not supported.");
    }

    @Override
    public void doUpdateCredentialWithID(String userID, Object newCredential, Object oldCredential)
            throws UserStoreException {

        if (!isSSLConnection) {
            logger.warn("Unsecured connection is being used. Password operations will fail");
        }

        String userName = doGetUserNameFromUserID(userID);
        DirContext dirContext = this.connectionSource.getContext();
        String searchBase = realmConfig.getUserStoreProperty(LDAPConstants.USER_SEARCH_BASE);
        String searchFilter = realmConfig.getUserStoreProperty(LDAPConstants.USER_NAME_SEARCH_FILTER);
        searchFilter = searchFilter.replace("?", escapeSpecialCharactersForFilter(userName));

        SearchControls searchControl = new SearchControls();
        String[] returningAttributes = { "CN" };
        searchControl.setReturningAttributes(returningAttributes);
        searchControl.setSearchScope(SearchControls.SUBTREE_SCOPE);
        DirContext subDirContext = null;
        NamingEnumeration<SearchResult> searchResults = null;

        Secret credentialObj;
        try {
            credentialObj = Secret.getSecret(newCredential);
        } catch (UnsupportedSecretTypeException e) {
            throw new UserStoreException("Unsupported credential type", e);
        }

        if (logger.isDebugEnabled()) {
            try {
                if (dirContext != null) {
                    logger.debug(
                            "Searching for user with SearchFilter: " + searchFilter + " in SearchBase: " + dirContext
                                    .getNameInNamespace());
                }
            } catch (NamingException e) {
                logger.debug("Error while getting DN of search base", e);
            }
        }

        try {
            // search the user with UserNameAttribute and obtain its CN attribute
            searchResults = dirContext.search(escapeDNForSearch(searchBase), searchFilter, searchControl);
            SearchResult user = null;
            int count = 0;
            while (searchResults.hasMoreElements()) {
                if (count > 0) {
                    throw new UserStoreException(
                            "There are more than one result in the user store " + "for user: " + userName);
                }
                user = searchResults.next();
                count++;
            }
            if (user == null) {
                throw new UserStoreException("User :" + userName + " does not Exist");
            }

            ModificationItem[] mods = null;

            // The user tries to change his own password
            if (oldCredential != null && newCredential != null) {
                mods = new ModificationItem[1];
                mods[0] = new ModificationItem(DirContext.REPLACE_ATTRIBUTE,
                        new BasicAttribute(LDAPConstants.ACTIVE_DIRECTORY_UNICODE_PASSWORD_ATTRIBUTE,
                                createUnicodePassword(credentialObj)));
            }
            subDirContext = (DirContext) dirContext.lookup(escapeDNForSearch(searchBase));
            subDirContext.modifyAttributes(user.getName(), mods);

        } catch (NamingException e) {
            String error = "Can not access the directory service for user : " + userName;
            if (logger.isDebugEnabled()) {
                logger.debug(error, e);
            }
            throw new UserStoreException(error, e);
        } finally {
            credentialObj.clear();
            JNDIUtil.closeNamingEnumeration(searchResults);
            JNDIUtil.closeContext(subDirContext);
            JNDIUtil.closeContext(dirContext);
        }

    }

    @Override
    public void doUpdateCredentialByAdmin(String userName, Object newCredential) throws UserStoreException {

        throw new UserStoreException("Operation is not supported.");
    }

    @Override
    public void doUpdateCredentialByAdminWithID(String userID, Object newCredential) throws UserStoreException {

        if (!isSSLConnection) {
            logger.warn("Unsecured connection is being used. Password operations will fail");
        }

        String userName = doGetUserNameFromUserID(userID);
        DirContext dirContext = this.connectionSource.getContext();
        String searchBase = realmConfig.getUserStoreProperty(LDAPConstants.USER_SEARCH_BASE);
        String searchFilter = realmConfig.getUserStoreProperty(LDAPConstants.USER_NAME_SEARCH_FILTER);
        searchFilter = searchFilter.replace("?", escapeSpecialCharactersForFilter(userName));
        SearchControls searchControl = new SearchControls();
        String[] returningAttributes = { "CN" };
        searchControl.setReturningAttributes(returningAttributes);
        searchControl.setSearchScope(SearchControls.SUBTREE_SCOPE);

        DirContext subDirContext = null;
        NamingEnumeration<SearchResult> searchResults = null;
        try {
            // search the user with UserNameAttribute and obtain its CN attribute
            searchResults = dirContext.search(escapeDNForSearch(searchBase), searchFilter, searchControl);
            if (logger.isDebugEnabled()) {
                try {
                    if (dirContext != null) {
                        logger.debug("Searching for user with SearchFilter: " + searchFilter + " in SearchBase: "
                                + dirContext.getNameInNamespace());
                    }
                } catch (NamingException e) {
                    logger.debug("Error while getting DN of search base", e);
                }
            }
            SearchResult user = null;
            int count = 0;
            while (searchResults.hasMoreElements()) {
                if (count > 0) {
                    throw new UserStoreException(
                            "There are more than one result in the user store " + "for user: " + userName);
                }
                user = searchResults.next();
                count++;
            }
            if (user == null) {
                throw new UserStoreException("User :" + userName + " does not Exist");
            }

            ModificationItem[] mods;

            if (newCredential != null) {
                Secret credentialObj;
                try {
                    credentialObj = Secret.getSecret(newCredential);
                } catch (UnsupportedSecretTypeException e) {
                    throw new UserStoreException("Unsupported credential type", e);
                }

                try {
                    mods = new ModificationItem[1];
                    mods[0] = new ModificationItem(DirContext.REPLACE_ATTRIBUTE,
                            new BasicAttribute(LDAPConstants.ACTIVE_DIRECTORY_UNICODE_PASSWORD_ATTRIBUTE,
                                    createUnicodePassword(credentialObj)));

                    subDirContext = (DirContext) dirContext.lookup(escapeDNForSearch(searchBase));
                    subDirContext.modifyAttributes(user.getName(), mods);
                } finally {
                    credentialObj.clear();
                }
            }

        } catch (NamingException e) {
            String error = "Can not access the directory service for user : " + userName;
            if (logger.isDebugEnabled()) {
                logger.debug(error, e);
            }
            throw new UserStoreException(error, e);
        } finally {
            JNDIUtil.closeNamingEnumeration(searchResults);
            JNDIUtil.closeContext(subDirContext);
            JNDIUtil.closeContext(dirContext);
        }
    }

    /**
     * This is to read and validate the required user store configuration for this user store
     * manager to take decisions.
     *
     * @throws UserStoreException
     */
    protected void checkRequiredUserStoreConfigurations() throws UserStoreException {

        super.checkRequiredUserStoreConfigurations();

        String is_ADLDSRole = realmConfig.getUserStoreProperty(LDAPConstants.ACTIVE_DIRECTORY_LDS_ROLE);
        isADLDSRole = Boolean.parseBoolean(is_ADLDSRole);

        if (!isADLDSRole) {
            userAccountControl = realmConfig.getUserStoreProperty(LDAPConstants.ACTIVE_DIRECTORY_USER_ACCOUNT_CONTROL);
            try {
                Integer.parseInt(userAccountControl);
            } catch (NumberFormatException e) {
                userAccountControl = "512";
            }
        }

        String connectionURL = realmConfig.getUserStoreProperty(LDAPConstants.CONNECTION_URL);
        String[] array = connectionURL.split(":");
        boolean startTLSEnabled = Boolean
                .parseBoolean(realmConfig.getUserStoreProperty(UserStoreConfigConstants.STARTTLS_ENABLED));
        if (array[0].equals("ldaps") || startTLSEnabled) {
            this.isSSLConnection = true;
        } else {
            logger.warn("Connection to the Active Directory is not secure. Password involved operations "
                    + "such as update credentials and adduser operations will fail");
        }
    }

    /**
     * Returns password as a UTF_16LE encoded bytes array
     *
     * @param password password instance of Secret
     * @return byte[]
     */
    private byte[] createUnicodePassword(Secret password) {
        char[] passwordChars = password.getChars();
        char[] quotedPasswordChars = new char[passwordChars.length + 2];

        for (int i = 0; i < quotedPasswordChars.length; i++) {
            if (i == 0 || i == quotedPasswordChars.length - 1) {
                quotedPasswordChars[i] = '"';
            } else {
                quotedPasswordChars[i] = passwordChars[i - 1];
            }
        }

        password.setChars(quotedPasswordChars);

        return password.getBytes(StandardCharsets.UTF_16LE);
    }

    @Override
    public void doSetUserClaimValues(String userName, Map<String, String> claims, String profileName)
            throws UserStoreException {

        throw new UserStoreException("Operation is not supported.");
    }

    @Override
    public void doSetUserClaimValues(String userName, Map<String, List<String>> multiValuedClaimsToAdd,
                                     Map<String, List<String>> multiValuedClaimsToDelete,
                                     Map<String, List<String>> claimsExcludingMultiValuedClaims,
                                     String profileName) throws UserStoreException {

        throw new UserStoreException("Operation is not supported.");
    }

    @Override
    protected void handleLdapUserIdAttributeChanges(Map<String, ? extends Object> userAttributeValues,
                                                    DirContext subDirContext, String returnedUserEntry)
            throws NamingException {

        if (userAttributeValues.containsKey(LDAPConstants.CN)) {
            String commonName = StringUtils.EMPTY;
            if (userAttributeValues.get(LDAPConstants.CN) instanceof String) {
                commonName = (String) userAttributeValues.get(LDAPConstants.CN);
            } else if (userAttributeValues.get(LDAPConstants.CN) instanceof List) {
                commonName = (String) ((List) userAttributeValues.get(LDAPConstants.CN)).get(0);
            }
            subDirContext.rename(returnedUserEntry,
                    LDAPConstants.CN_CAPITALIZED + "=" + escapeSpecialCharactersForDN(commonName));
            userAttributeValues.remove(LDAPConstants.CN);
        }
    }

    @Override
    public void doSetUserClaimValue(String userName, String claimURI, String claimValue, String profileName)
            throws UserStoreException {

        throw new UserStoreException("Operation is not supported.");
    }

    @Override
    public void doSetUserAttributeWithID(String userID, String attributeName, String value, String profileName)
            throws UserStoreException {
        // Get the LDAP Directory context.
        DirContext dirContext = this.connectionSource.getContext();
        DirContext subDirContext = null;

        // Search the relevant user entry by user name.
        String userSearchBase = realmConfig.getUserStoreProperty(LDAPConstants.USER_SEARCH_BASE);
        String userSearchFilter = realmConfig.getUserStoreProperty(LDAPConstants.USER_ID_SEARCH_FILTER);
        String userIDAttribute = realmConfig.getUserStoreProperty(LDAPConstants.USER_ID_ATTRIBUTE);

        userSearchFilter = userSearchFilter.replaceAll(LDAPConstants.UID_EXACT_MATCH, userIDAttribute);

        if (OBJECT_GUID.equalsIgnoreCase(userIDAttribute) && isBinaryUserAttribute(userIDAttribute)) {
            userID = transformUUIDToObjectGUID(userID);
            userSearchFilter = userSearchFilter.replace("?", userID);
        } else {
            userSearchFilter = userSearchFilter.replace("?", escapeSpecialCharactersForFilter(userID));
        }

        SearchControls searchControls = new SearchControls();
        searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        searchControls.setReturningAttributes(null);

        NamingEnumeration<SearchResult> returnedResultList = null;
        String returnedUserEntry;

        try {
            returnedResultList = dirContext.search(escapeDNForSearch(userSearchBase), userSearchFilter, searchControls);
            // Assume only one user is returned from the search.
            returnedUserEntry = returnedResultList.next().getName();
        } catch (NamingException e) {
            String errorMessage = "Results could not be retrieved from the directory context for user : " + userID;

            if (logger.isDebugEnabled()) {
                logger.debug(errorMessage, e);
            }

            throw new UserStoreException(errorMessage, e);
        } finally {
            JNDIUtil.closeNamingEnumeration(returnedResultList);
        }

        try {
            Attributes updatedAttributes = new BasicAttributes(true);

            if (LDAPConstants.CN_CAPITALIZED.equals(attributeName)) {
                subDirContext = (DirContext) dirContext.lookup(escapeDNForSearch(userSearchBase));
                subDirContext.rename(returnedUserEntry, LDAPConstants.CN_CAPITALIZED + "=" + value);
                return;
            }

            Attribute currentUpdatedAttribute = new BasicAttribute(attributeName);
            // If updated attribute value is null, remove its values.
            if (EMPTY_ATTRIBUTE_STRING.equals(value)) {
                currentUpdatedAttribute.clear();
            } else {
                String claimSeparator = realmConfig.getUserStoreProperty(MULTI_ATTRIBUTE_SEPARATOR);

                if (claimSeparator != null && !claimSeparator.trim().isEmpty()) {
                    userAttributeSeparator = claimSeparator;
                }

                if (value.contains(userAttributeSeparator)) {
                    StringTokenizer st = new StringTokenizer(value, userAttributeSeparator);

                    while (st.hasMoreElements()) {
                        String newVal = st.nextElement().toString();

                        if (newVal != null && newVal.trim().length() > 0) {
                            currentUpdatedAttribute.add(newVal.trim());
                        }
                    }
                } else {
                    currentUpdatedAttribute.add(value);
                }
            }
            updatedAttributes.put(currentUpdatedAttribute);

            // Update the attributes in the relevant entry of the directory store.
            subDirContext = (DirContext) dirContext.lookup(escapeDNForSearch(userSearchBase));
            subDirContext.modifyAttributes(returnedUserEntry, DirContext.REPLACE_ATTRIBUTE, updatedAttributes);

        } catch (Exception e) {
            handleException(e, userID);
        } finally {
            JNDIUtil.closeContext(subDirContext);
            JNDIUtil.closeContext(dirContext);
        }

    }

    @Override
    public Properties getDefaultUserStoreProperties() {

        Properties properties = new Properties();

        properties.setMandatoryProperties(
                Stream.concat(ActiveDirectoryUserStoreConstants.ACTIVE_DIRECTORY_UM_PROPERTIES.stream(),
                        ActiveDirectoryUserStoreConstants.UNIQUE_ID_ACTIVE_DIRECTORY_UM_PROPERTIES.stream())
                        .toArray(Property[]::new));
        properties.setOptionalProperties(
                ActiveDirectoryUserStoreConstants.OPTIONAL_ACTIVE_DIRECTORY_UM_PROPERTIES.toArray(new Property[0]));
        properties.setAdvancedProperties(UNIQUE_ID_ACTIVE_DIRECTORY_UM_ADVANCED_PROPERTIES.toArray(new Property[0]));

        return properties;
    }

    private void handleException(Exception e, String userName) throws UserStoreException {

        if (e instanceof InvalidAttributeValueException) {
            String errorMessage = "One or more attribute values provided are incompatible for user : " + userName
                    + "Please check and try again.";
            if (logger.isDebugEnabled()) {
                logger.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        } else if (e instanceof InvalidAttributeIdentifierException) {
            String errorMessage = "One or more attributes you are trying to add/update are not "
                    + "supported by underlying LDAP for user : " + userName;
            if (logger.isDebugEnabled()) {
                logger.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        } else if (e instanceof NoSuchAttributeException) {
            String errorMessage = "One or more attributes you are trying to add/update are not "
                    + "supported by underlying LDAP for user : " + userName;
            if (logger.isDebugEnabled()) {
                logger.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        } else if (e instanceof NamingException) {
            String errorMessage = "Profile information could not be updated in LDAP user store for user : " + userName;
            if (logger.isDebugEnabled()) {
                logger.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        } else if (e instanceof org.wso2.carbon.user.api.UserStoreException) {
            String errorMessage = "Error in obtaining claim mapping for user : " + userName;
            if (logger.isDebugEnabled()) {
                logger.debug(errorMessage, e);
            }
            throw new UserStoreException(errorMessage, e);
        }
    }

    /**
     * Escaping ldap search filter special characters in a string
     *
     * @param dnPartial
     * @return
     */
    private String escapeSpecialCharactersForFilter(String dnPartial) {

        boolean replaceEscapeCharacters = true;
        String replaceEscapeCharactersAtUserLoginString = realmConfig
                .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_REPLACE_ESCAPE_CHARACTERS_AT_USER_LOGIN);

        if (replaceEscapeCharactersAtUserLoginString != null) {
            replaceEscapeCharacters = Boolean.parseBoolean(replaceEscapeCharactersAtUserLoginString);
            if (logger.isDebugEnabled()) {
                logger.debug("Replace escape characters configured to: " + replaceEscapeCharactersAtUserLoginString);
            }
        }

        if (replaceEscapeCharacters) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < dnPartial.length(); i++) {
                char currentChar = dnPartial.charAt(i);
                switch (currentChar) {
                case '\\':
                    sb.append("\\5c");
                    break;
                case '(':
                    sb.append("\\28");
                    break;
                case ')':
                    sb.append("\\29");
                    break;
                case '\u0000':
                    sb.append("\\00");
                    break;
                default:
                    sb.append(currentChar);
                }
            }
            return sb.toString();
        } else {
            return dnPartial;
        }
    }

    /**
     * Escaping ldap DN special characters in a String value
     *
     * @param text
     * @return
     */
    private String escapeSpecialCharactersForDN(String text) {

        boolean replaceEscapeCharacters = true;
        String replaceEscapeCharactersAtUserLoginString = realmConfig
                .getUserStoreProperty(UserCoreConstants.RealmConfig.PROPERTY_REPLACE_ESCAPE_CHARACTERS_AT_USER_LOGIN);

        if (replaceEscapeCharactersAtUserLoginString != null) {
            replaceEscapeCharacters = Boolean.parseBoolean(replaceEscapeCharactersAtUserLoginString);
            if (logger.isDebugEnabled()) {
                logger.debug("Replace escape characters configured to: " + replaceEscapeCharactersAtUserLoginString);
            }
        }

        if (replaceEscapeCharacters) {
            StringBuilder sb = new StringBuilder();
            if ((text.length() > 0) && ((text.charAt(0) == ' ') || (text.charAt(0) == '#'))) {
                sb.append('\\'); // add the leading backslash if needed
            }
            for (int i = 0; i < text.length(); i++) {
                char currentChar = text.charAt(i);
                switch (currentChar) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case ',':
                    sb.append("\\,");
                    break;
                case '+':
                    sb.append("\\+");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '<':
                    sb.append("\\<");
                    break;
                case '>':
                    sb.append("\\>");
                    break;
                case ';':
                    sb.append("\\;");
                    break;
                default:
                    sb.append(currentChar);
                }
            }
            if ((text.length() > 1) && (text.charAt(text.length() - 1) == ' ')) {
                sb.insert(sb.length() - 1, '\\'); // add the trailing backslash if needed
            }
            if (logger.isDebugEnabled()) {
                logger.debug("value after escaping special characters in " + text + " : " + sb.toString());
            }
            return sb.toString();
        } else {
            return text;
        }
    }

    private static void setAdvancedProperties() {

        // Set Advanced Properties
        UNIQUE_ID_ACTIVE_DIRECTORY_UM_ADVANCED_PROPERTIES.clear();
        setAdvancedProperty(BULK_IMPORT_SUPPORT, "Bulk Import Support", "true", "Bulk Import Supported");
        setAdvancedProperty(UserStoreConfigConstants.emptyRolesAllowed, "Allow Empty Roles", "true",
                UserStoreConfigConstants.emptyRolesAllowedDescription);

        setAdvancedProperty(UserStoreConfigConstants.passwordHashMethod, "Password Hashing Algorithm", "PLAIN_TEXT",
                UserStoreConfigConstants.passwordHashMethodDescription);
        setAdvancedProperty(MULTI_ATTRIBUTE_SEPARATOR, "Multiple Attribute Separator", ",",
                MULTI_ATTRIBUTE_SEPARATOR_DESCRIPTION);
        setAdvancedProperty("isADLDSRole", "Is ADLDS Role", "false",
                "Whether an Active Directory Lightweight Directory Services role");
        setAdvancedProperty("userAccountControl", "User Account Control", "512",
                "Flags that control the behavior of the user account");

        setAdvancedProperty(UserStoreConfigConstants.maxUserNameListLength, "Maximum User List Length", "100",
                UserStoreConfigConstants.maxUserNameListLengthDescription);
        setAdvancedProperty(UserStoreConfigConstants.maxRoleNameListLength, "Maximum Role List Length", "100",
                UserStoreConfigConstants.maxRoleNameListLengthDescription);

        setAdvancedProperty("kdcEnabled", "Enable KDC", "false", "Whether key distribution center enabled");
        setAdvancedProperty("defaultRealmName", "Default Realm Name", "WSO2.ORG", "Default name for the realm");

        setAdvancedProperty(UserStoreConfigConstants.userRolesCacheEnabled, "Enable User Role Cache", "true",
                UserStoreConfigConstants.userRolesCacheEnabledDescription);

        setAdvancedProperty(UserStoreConfigConstants.connectionPoolingEnabled, "Enable LDAP Connection Pooling",
                "false", UserStoreConfigConstants.connectionPoolingEnabledDescription);

        setAdvancedProperty(LDAPConnectionTimeout, "LDAP Connection Timeout", "5000", LDAPConnectionTimeoutDescription);
        setAdvancedProperty(readTimeout, "LDAP Read Timeout", "5000", readTimeoutDescription);
        setAdvancedProperty(RETRY_ATTEMPTS, "Retry Attempts", "0",
                "Number of retries for" + " authentication in case ldap read timed out.");
        setAdvancedProperty("CountRetrieverClass", "Count Implementation", "",
                "Name of the class that implements the count functionality");
        setAdvancedProperty(LDAPConstants.LDAP_ATTRIBUTES_BINARY, "LDAP binary attributes",
                UserStoreConfigConstants.OBJECT_GUID,
                LDAPBinaryAttributesDescription);
        setAdvancedProperty(UserStoreConfigConstants.claimOperationsSupported,
                UserStoreConfigConstants.getClaimOperationsSupportedDisplayName, "true",
                UserStoreConfigConstants.claimOperationsSupportedDescription);
        setAdvancedProperty(ActiveDirectoryUserStoreConstants.TRANSFORM_OBJECTGUID_TO_UUID,
                ActiveDirectoryUserStoreConstants.TRANSFORM_OBJECTGUID_TO_UUID_DESC, "true",
                ActiveDirectoryUserStoreConstants.TRANSFORM_OBJECTGUID_TO_UUID_DESC);
        setAdvancedProperty(MEMBERSHIP_ATTRIBUTE_RANGE, MEMBERSHIP_ATTRIBUTE_RANGE_DISPLAY_NAME,
                String.valueOf(MEMBERSHIP_ATTRIBUTE_RANGE_VALUE), "Number of maximum users of role returned by the AD");

        setAdvancedProperty(LDAPConstants.USER_CACHE_EXPIRY_MILLISECONDS, USER_CACHE_EXPIRY_TIME_ATTRIBUTE_NAME, "",
                USER_CACHE_EXPIRY_TIME_ATTRIBUTE_DESCRIPTION);
        setAdvancedProperty(LDAPConstants.USER_DN_CACHE_ENABLED, USER_DN_CACHE_ENABLED_ATTRIBUTE_NAME, "true",
                USER_DN_CACHE_ENABLED_ATTRIBUTE_DESCRIPTION);
        setAdvancedProperty(UserStoreConfigConstants.STARTTLS_ENABLED,
                UserStoreConfigConstants.STARTTLS_ENABLED_DISPLAY_NAME, "false",
                UserStoreConfigConstants.STARTTLS_ENABLED_DESCRIPTION);
        setAdvancedProperty(UserStoreConfigConstants.CONNECTION_RETRY_DELAY,
                UserStoreConfigConstants.CONNECTION_RETRY_DELAY_DISPLAY_NAME,
                String.valueOf(UserStoreConfigConstants.DEFAULT_CONNECTION_RETRY_DELAY_IN_MILLISECONDS),
                UserStoreConfigConstants.CONNECTION_RETRY_DELAY_DESCRIPTION);
        setAdvancedProperty(UserStoreConfigConstants.immutableAttributes,
                UserStoreConfigConstants.immutableAttributesDisplayName, UserStoreConfigConstants.OBJECT_GUID,
                UserStoreConfigConstants.immutableAttributesDescription);
        setAdvancedProperty(UserStoreConfigConstants.timestampAttributes,
                UserStoreConfigConstants.timestampAttributesDisplayName, " ",
                UserStoreConfigConstants.timestampAttributesDescription);
    }

    private static void setAdvancedProperty(String name, String displayName, String value, String description) {

        Property property = new Property(name, value, displayName + "#" + description, null);
        UNIQUE_ID_ACTIVE_DIRECTORY_UM_ADVANCED_PROPERTIES.add(property);
    }

    @Override
    protected void processAttributesBeforeUpdateWithID(String userID, Map<String, ? extends Object> userStorePropertyValues,
                                                       String profileName) {

        String immutableAttributesProperty = Optional.ofNullable(realmConfig
                .getUserStoreProperty(UserStoreConfigConstants.immutableAttributes)).orElse(StringUtils.EMPTY);

        String[] immutableAttributes = StringUtils.split(immutableAttributesProperty, ",");

        if (logger.isDebugEnabled()) {
            logger.debug("Retrieved user store properties for update: " + userStorePropertyValues);
        }

        if (ArrayUtils.isNotEmpty(immutableAttributes)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Skipping Active Directory maintained default attributes: "
                        + Arrays.toString(immutableAttributes));
            }

            Arrays.stream(immutableAttributes).forEach(userStorePropertyValues::remove);
        }
    }

    @Override
    protected void processAttributesAfterRetrievalWithID(String userID, Map<String, String> userStorePropertyValues,
                                                         String profileName) {

        String timestampAttributesProperty = Optional.ofNullable(realmConfig
                .getUserStoreProperty(UserStoreConfigConstants.timestampAttributes)).orElse(StringUtils.EMPTY);

        String[] timestampAttributes = StringUtils.split(timestampAttributesProperty, ",");

        if (logger.isDebugEnabled()) {
            logger.debug("Active Directory timestamp attributes: " + Arrays.toString(timestampAttributes));
        }

        if (ArrayUtils.isNotEmpty(timestampAttributes)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Retrieved user store properties before type conversions: " + userStorePropertyValues);
            }

            Map<String, String> convertedTimestampAttributeValues = Arrays.stream(timestampAttributes)
                    .filter(attribute -> userStorePropertyValues.get(attribute) != null)
                    .collect(Collectors.toMap(Function.identity(),
                            attribute -> convertDateFormatFromAD(userStorePropertyValues.get(attribute))));

            if (logger.isDebugEnabled()) {
                logger.debug("Converted timestamp attribute values: " + convertedTimestampAttributeValues);
            }

            userStorePropertyValues.putAll(convertedTimestampAttributeValues);

            if (logger.isDebugEnabled()) {
                logger.debug("Retrieved user store properties after type conversions: " + userStorePropertyValues);
            }
        }
    }

    /**
     * Convert Active Directory date format (Generalized Time) to WSO2 format.
     *
     * @param date Date formatted in Active Directory date format.
     * @return Date formatted in WSO2 date format.
     */
    private String convertDateFormatFromAD(String date) {

        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern(ACTIVE_DIRECTORY_DATE_TIME_FORMAT);
        OffsetDateTime offsetDateTime = OffsetDateTime.parse(date, dateTimeFormatter);
        Instant instant = offsetDateTime.toInstant();
        return instant.toString();
    }
}
