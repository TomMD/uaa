package org.cloudfoundry.identity.uaa.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.cloudfoundry.identity.uaa.audit.event.EntityDeletedEvent;
import org.cloudfoundry.identity.uaa.authentication.SystemAuthentication;
import org.cloudfoundry.identity.uaa.oauth.client.ClientConstants;
import org.cloudfoundry.identity.uaa.user.UaaAuthority;
import org.cloudfoundry.identity.uaa.zone.ClientServicesExtension;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ApplicationEventPublisherAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.provider.ClientAlreadyExistsException;
import org.springframework.security.oauth2.provider.ClientDetails;
import org.springframework.security.oauth2.provider.NoSuchClientException;
import org.springframework.security.oauth2.provider.client.BaseClientDetails;
import org.springframework.util.StringUtils;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

import static java.util.Collections.emptyList;
import static java.util.Optional.ofNullable;
import static org.cloudfoundry.identity.uaa.oauth.token.TokenConstants.*;

public class ClientAdminBootstrap implements
        InitializingBean,
        ApplicationListener<ContextRefreshedEvent>,
        ApplicationEventPublisherAware {

    private static Logger logger = LoggerFactory.getLogger(ClientAdminBootstrap.class);

    private final PasswordEncoder passwordEncoder;
    private final ClientServicesExtension clientRegistrationService;
    private final ClientMetadataProvisioning clientMetadataProvisioning;
    private ApplicationEventPublisher publisher;

    private Map<String, Map<String, Object>> clients = new HashMap<>();
    private List<String> clientsToDelete = null;
    private Collection<String> autoApproveClients = Collections.emptySet();
    private boolean defaultOverride = true;

    ClientAdminBootstrap(
            final PasswordEncoder passwordEncoder,
            final ClientServicesExtension clientRegistrationService,
            final ClientMetadataProvisioning clientMetadataProvisioning) {
        this.passwordEncoder = passwordEncoder;
        this.clientRegistrationService = clientRegistrationService;
        this.clientMetadataProvisioning = clientMetadataProvisioning;
    }

    /**
     * Flag to indicate that client details should override existing values by
     * default. If true and the override flag is
     * not set in the client details input then the details will override any
     * existing details with the same id.
     *
     * @param defaultOverride the default override flag to set (default true, so
     *                        flag does not have to be provided
     *                        explicitly)
     */
    public void setDefaultOverride(boolean defaultOverride) {
        this.defaultOverride = defaultOverride;
    }

    public PasswordEncoder getPasswordEncoder() {
        return passwordEncoder;
    }

    /**
     * @param clients the clients to set
     */
    public void setClients(Map<String, Map<String, Object>> clients) {
        if (clients == null) {
            this.clients = Collections.emptyMap();
        } else {
            this.clients = new HashMap<>(clients);
        }
    }

    public void setClientsToDelete(List<String> clientsToDelete) {
        this.clientsToDelete = clientsToDelete;
    }

    /**
     * A set of client ids that are unconditionally to be autoapproved
     * (independent of the settings in the client
     * details map). These clients will have <code>autoapprove=true</code> when
     * they are inserted into the client
     * details store.
     *
     * @param autoApproveClients the auto approve clients
     */
    public void setAutoApproveClients(Collection<String> autoApproveClients) {
        this.autoApproveClients = autoApproveClients;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        addNewClients();
        updateAutoApproveClients();
    }

    /**
     * Explicitly override autoapprove in all clients that were provided in the
     * whitelist.
     */
    private void updateAutoApproveClients() {
        List<String> slatedForDeletion = ofNullable(clientsToDelete).orElse(emptyList());
        Collection<String> autoApproveList = new LinkedList(ofNullable(autoApproveClients).orElse(emptyList()));
        autoApproveList.removeIf(s -> slatedForDeletion.contains(s));
        for (String clientId : autoApproveList) {
            try {
                BaseClientDetails base = (BaseClientDetails) clientRegistrationService.loadClientByClientId(clientId, IdentityZone.getUaaZoneId());
                base.addAdditionalInformation(ClientConstants.AUTO_APPROVE, true);
                logger.debug("Adding autoapprove flag to client: " + clientId);
                clientRegistrationService.updateClientDetails(base, IdentityZone.getUaaZoneId());
            } catch (NoSuchClientException n) {
                logger.debug("Client not found, unable to set autoapprove: " + clientId);
            }
        }
    }

    private String getRedirectUris(Map<String, Object> map) {
        Set<String> redirectUris = new HashSet<>();
        if (map.get("redirect-uri") != null) {
            redirectUris.add((String) map.get("redirect-uri"));
        }
        if (map.get("signup_redirect_url") != null) {
            redirectUris.add((String) map.get("signup_redirect_url"));
        }
        if (map.get("change_email_redirect_url") != null) {
            redirectUris.add((String) map.get("change_email_redirect_url"));
        }
        return StringUtils.arrayToCommaDelimitedString(redirectUris.toArray(new String[]{}));
    }

    private void addNewClients() {
        List<String> slatedForDeletion = ofNullable(clientsToDelete).orElse(emptyList());
        Set<Map.Entry<String, Map<String, Object>>> entries = clients.entrySet();
        entries.removeIf(entry -> slatedForDeletion.contains(entry.getKey()));
        for (Map.Entry<String, Map<String, Object>> entry : entries) {
            String clientId = entry.getKey();

            Map<String, Object> map = entry.getValue();
            if (map.get("authorized-grant-types") == null) {
                throw new InvalidClientDetailsException("Client must have at least one authorized-grant-type. client ID: " + clientId);
            }
            BaseClientDetails client = new BaseClientDetails(clientId, (String) map.get("resource-ids"),
                    (String) map.get("scope"), (String) map.get("authorized-grant-types"),
                    (String) map.get("authorities"), getRedirectUris(map));

            client.setClientSecret(map.get("secret") == null ? "" : (String) map.get("secret"));

            Integer validity = (Integer) map.get("access-token-validity");
            Boolean override = (Boolean) map.get("override");
            if (override == null) {
                override = defaultOverride;
            }
            Map<String, Object> info = new HashMap<>(map);
            if (validity != null) {
                client.setAccessTokenValiditySeconds(validity);
            }
            validity = (Integer) map.get("refresh-token-validity");
            if (validity != null) {
                client.setRefreshTokenValiditySeconds(validity);
            }
            // UAA does not use the resource ids in client registrations
            client.setResourceIds(Collections.singleton("none"));
            if (client.getScope().isEmpty()) {
                client.setScope(Collections.singleton("uaa.none"));
            }
            if (client.getAuthorities().isEmpty()) {
                client.setAuthorities(Collections.singleton(UaaAuthority.UAA_NONE));
            }
            if (client.getAuthorizedGrantTypes().contains(GRANT_TYPE_AUTHORIZATION_CODE)) {
                client.getAuthorizedGrantTypes().add(GRANT_TYPE_REFRESH_TOKEN);
            }
            for (String key : Arrays.asList("resource-ids", "scope", "authorized-grant-types", "authorities",
                    "redirect-uri", "secret", "id", "override", "access-token-validity",
                    "refresh-token-validity", "show-on-homepage", "app-launch-url", "app-icon")) {
                info.remove(key);
            }

            client.setAdditionalInformation(info);
            try {
                clientRegistrationService.addClientDetails(client, IdentityZone.getUaaZoneId());
            } catch (ClientAlreadyExistsException e) {
                if (override) {
                    logger.debug("Overriding client details for " + clientId);
                    clientRegistrationService.updateClientDetails(client, IdentityZone.getUaaZoneId());
                    if (didPasswordChange(clientId, client.getClientSecret())) {
                        clientRegistrationService.updateClientSecret(clientId, client.getClientSecret(), IdentityZone.getUaaZoneId());
                    }
                } else {
                    // ignore it
                    logger.debug(e.getMessage());
                }
            }

            for (String s : Arrays.asList(GRANT_TYPE_AUTHORIZATION_CODE, GRANT_TYPE_IMPLICIT)) {
                if (client.getAuthorizedGrantTypes().contains(s) && isMissingRedirectUris(client)) {
                    throw new InvalidClientDetailsException(s + " grant type requires at least one redirect URL. ClientID: " + client.getClientId());
                }
            }

            ClientMetadata clientMetadata = buildClientMetadata(map, clientId);
            clientMetadataProvisioning.update(clientMetadata, IdentityZoneHolder.get().getId());
        }
    }

    private boolean isMissingRedirectUris(BaseClientDetails client) {
        return client.getRegisteredRedirectUri() == null || client.getRegisteredRedirectUri().isEmpty();
    }

    private ClientMetadata buildClientMetadata(Map<String, Object> map, String clientId) {
        Boolean showOnHomepage = (Boolean) map.get("show-on-homepage");
        String appLaunchUrl = (String) map.get("app-launch-url");
        String appIcon = (String) map.get("app-icon");
        ClientMetadata clientMetadata = new ClientMetadata();
        clientMetadata.setClientId(clientId);

        clientMetadata.setAppIcon(appIcon);
        clientMetadata.setShowOnHomePage(showOnHomepage != null && showOnHomepage);
        if (StringUtils.hasText(appLaunchUrl)) {
            try {
                clientMetadata.setAppLaunchUrl(new URL(appLaunchUrl));
            } catch (MalformedURLException e) {
                logger.info("Client metadata exception", new ClientMetadataException("Invalid app-launch-url for client " + clientId, e, HttpStatus.INTERNAL_SERVER_ERROR));
            }
        }

        return clientMetadata;
    }

    private boolean didPasswordChange(String clientId, String rawPassword) {
        if (getPasswordEncoder() != null) {
            ClientDetails existing = clientRegistrationService.loadClientByClientId(clientId, IdentityZoneHolder.get().getId());
            String existingPasswordHash = existing.getClientSecret();
            return !getPasswordEncoder().matches(rawPassword, existingPasswordHash);
        } else {
            return true;
        }
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        Authentication auth = SystemAuthentication.SYSTEM_AUTHENTICATION;
        for (String clientId : ofNullable(clientsToDelete).orElse(emptyList())) {
            try {
                ClientDetails client = clientRegistrationService.loadClientByClientId(clientId, IdentityZoneHolder.get().getId());
                logger.debug("Deleting client from manifest:" + clientId);
                EntityDeletedEvent<ClientDetails> delete = new EntityDeletedEvent<>(client, auth);
                publish(delete);
            } catch (NoSuchClientException e) {
                logger.debug("Ignoring delete for non existent client:" + clientId);
            }
        }
    }

    @Override
    public void setApplicationEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.publisher = applicationEventPublisher;
    }

    public void publish(ApplicationEvent event) {
        if (publisher != null) {
            publisher.publishEvent(event);
        }
    }
}
