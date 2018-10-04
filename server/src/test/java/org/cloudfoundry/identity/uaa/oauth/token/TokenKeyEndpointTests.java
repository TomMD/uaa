package org.cloudfoundry.identity.uaa.oauth.token;

import org.cloudfoundry.identity.uaa.oauth.KeyInfoService;
import org.cloudfoundry.identity.uaa.oauth.TokenKeyEndpoint;
import org.cloudfoundry.identity.uaa.oauth.jwk.JsonWebKeySet;
import org.cloudfoundry.identity.uaa.oauth.jwk.JsonWebKeyElements;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.util.MapCollector;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneConfiguration;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneProvisioning;
import org.cloudfoundry.identity.uaa.zone.MultitenancyFixture;
import org.cloudfoundry.identity.uaa.zone.TokenPolicy;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.jwt.crypto.sign.RsaSigner;
import org.springframework.security.jwt.crypto.sign.RsaVerifier;

import java.security.Principal;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class TokenKeyEndpointTests {

    private TokenKeyEndpoint tokenKeyEndpoint = new TokenKeyEndpoint(new KeyInfoService("https://localhost.uaa"));
    private Authentication validUaaResource;
    private final String SIGNING_KEY_1 = "-----BEGIN RSA PRIVATE KEY-----\n" +
      "MIIBOQIBAAJAcPh8sj6TdTGYUTAn7ywyqNuzPD8pNtmSFVm87yCIhKDdIdEQ+g8H\n" +
      "xq8zBWtMN9uaxyEomLXycgTbnduW6YOpyQIDAQABAkAE2qiBAC9V2cuxsWAF5uBG\n" +
      "YSpSbGRY9wBP6oszuzIigLgWwxYwqGSS/Euovn1/BZEQL1JLc8tRp+Zn34JfLrAB\n" +
      "AiEAz956b8BHk2Inbp2FcOvJZI4XVEah5ITY+vTvYFTQEz0CIQCLIN4t+ehu/qIS\n" +
      "fj94nT9LhKPJKMwqhZslC0tIJ4OpfQIhAKaruHhKMBnYpc1nuEsmg8CAvevxBnX4\n" +
      "nxH5usX+uyfxAiA0l7olWyEYRD10DDFmINs6auuXMUrskBDz0e8lWXqV6QIgJSkM\n" +
      "L5WgVmzexrNmKxmGQQhNzfgO0Lk7o+iNNZXbkxw=\n" +
      "-----END RSA PRIVATE KEY-----";
    private final String SIGNING_KEY_2 = "-----BEGIN RSA PRIVATE KEY-----\n" +
      "MIIBOQIBAAJBAKIuxhxq0SyeITbTw3SeyHz91eB6xEwRn9PPgl+klu4DRUmVs0h+\n" +
      "UlVjXSTLiJ3r1bJXVded4JzVvNSh5Nw+7zsCAwEAAQJAYeVH8klL39nHhLfIiHF7\n" +
      "5W63FhwktyIATrM4KBFKhXn8i29l76qVqX88LAYpeULric8fGgNoSaYVsHWIOgDu\n" +
      "cQIhAPCJ7hu7OgqvyIGWRp2G2qjKfQVqSntG9HNSt9MhaXKjAiEArJt+PoF0AQFR\n" +
      "R9O/XULmxR0OUYhkYZTr5eCo7kNscokCIDSv0aLrYKxEkqOn2fHZPv3n1HiiLoxQ\n" +
      "H20/OhqZ3/IHAiBSn3/31am8zW+l7UM+Fkc29aij+KDsYQfmmvriSp3/2QIgFtiE\n" +
      "Jkd0KaxkobLdyDrW13QnEaG5TXO0Y85kfu3nP5o=\n" +
      "-----END RSA PRIVATE KEY-----";
    private final String SIGNING_KEY_3 = "-----BEGIN RSA PRIVATE KEY-----\n" +
      "MIIBOgIBAAJBAOnndOyLh8axLMyjX+gCglBCeU5Cumjxz9asho5UvO8zf03PWciZ\n" +
      "DGWce+B+n23E1IXbRKHWckCY0UH7fEgbrKkCAwEAAQJAGR9aCJoH8EhRVn1prKKw\n" +
      "Wmx5WPWDzgfC2fzXyuvBCzPZNMQqOxWT9ajr+VysuyFZbz+HGJDqpf9Jl+fcIIUJ\n" +
      "LQIhAPTn319kLU0QzoNBSB53tPhdNbzggBpW/Xv6B52XqGwPAiEA9IAAFu7GVymQ\n" +
      "/neMHM7/umMFGFFbdq8E2pohLyjcg8cCIQCZWfv/0k2ffQ+jFqSfF1wFTPBSRc1R\n" +
      "MPlmwSg1oPpANwIgHngBCtqQnvYQGpX9QO3O0oRaczBYTI789Nz2O7FE4asCIGEy\n" +
      "SkbkWTex/hl+l0wdNErz/yBxP8esbPukOUqks/if\n" +
      "-----END RSA PRIVATE KEY-----";

    @Before
    public void setUp() throws Exception {
        validUaaResource = new UsernamePasswordAuthenticationToken("client_id", null, Collections.singleton(new SimpleGrantedAuthority("uaa.resource")));
    }

    @After
    public void cleanUp() {
        IdentityZoneHolder.clear();
    }

    @Test
    public void sharedSecretIsReturnedFromTokenKeyEndpoint() {
        configureKeysForDefaultZone(Collections.singletonMap("someKeyId", "someKey"));
        JsonWebKeyElements response = tokenKeyEndpoint.getKey(validUaaResource);
        assertEquals("HS256", response.getAlgorithm());
        assertEquals("someKey", response.getKey());
        assertEquals("someKeyId", response.getId());
        assertEquals("MAC", response.getType());
        assertEquals("sig", response.getUse().name());
    }

    @Test(expected = AccessDeniedException.class)
    public void sharedSecretCannotBeAnonymouslyRetrievedFromTokenKeyEndpoint() {
        configureKeysForDefaultZone(Collections.singletonMap("anotherKeyId", "someKey"));
        assertEquals("{alg=HMACSHA256, value=someKey}",
          tokenKeyEndpoint.getKey(
            new AnonymousAuthenticationToken("anon", "anonymousUser", AuthorityUtils
              .createAuthorityList("ROLE_ANONYMOUS"))).toString());
    }

    @Test
    public void responseIsBackwardCompatibleWithMap() {
        configureKeysForDefaultZone(Collections.singletonMap("literallyAnything", "someKey"));
        JsonWebKeyElements response = tokenKeyEndpoint.getKey(validUaaResource);

        String serialized = JsonUtils.writeValueAsString(response);

        Map<String, String> deserializedMap = JsonUtils.readValue(serialized, Map.class);
        assertEquals("HS256", deserializedMap.get("alg"));
        assertEquals("someKey", deserializedMap.get("value"));
        assertEquals("MAC", deserializedMap.get("kty"));
        assertEquals("sig", deserializedMap.get("use"));
    }

    @Test
    public void keyIsReturnedForZone() {
        createAndSetTestZoneWithKeys(Collections.singletonMap("key1", SIGNING_KEY_1));

        JsonWebKeyElements response = tokenKeyEndpoint.getKey(mock(Principal.class));
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        Base64.Decoder decoder = Base64.getUrlDecoder();

        assertEquals(response.getModulus(), encoder.encodeToString(decoder.decode(response.getModulus())));
        assertEquals(response.getExponent(), encoder.encodeToString(decoder.decode((response.getExponent()))));

        assertEquals("RS256", response.getAlgorithm());
        assertEquals("key1", response.getId());
        assertEquals("RSA", response.getType());
        assertEquals("sig", response.getUse().name());
    }

    @Test
    public void defaultZoneKeyIsReturned_ForZoneWithNoKeys() {
        configureKeysForDefaultZone(Collections.singletonMap("someKeyId", "someKey"));
        createAndSetTestZoneWithKeys(null);

        JsonWebKeyElements response = tokenKeyEndpoint.getKey(validUaaResource);

        assertEquals("HS256", response.getAlgorithm());
        assertEquals("someKey", response.getKey());
        assertEquals("someKeyId", response.getId());
        assertEquals("MAC", response.getType());
        assertEquals("sig", response.getUse().name());
    }

    @Test
    public void listResponseContainsAllPublicKeysWhenUnauthenticated() {
        Map<String, String> keysForUaaZone = new HashMap<>();
        keysForUaaZone.put("RsaKey1", SIGNING_KEY_1);
        keysForUaaZone.put("thisIsASymmetricKeyThatShouldNotShowUp", "ItHasSomeTextThatIsNotPEM");
        keysForUaaZone.put("RsaKey2", SIGNING_KEY_2);
        keysForUaaZone.put("RsaKey3", SIGNING_KEY_3);
        configureKeysForDefaultZone(keysForUaaZone);

        JsonWebKeySet keysResponse = tokenKeyEndpoint.getKeys(null);
        List<JsonWebKeyElements> keys = keysResponse.getKeys();
        List<String> keyIds = keys.stream().map(JsonWebKeyElements::getId).collect(Collectors.toList());
        assertThat(keyIds, containsInAnyOrder("RsaKey1", "RsaKey2", "RsaKey3"));

        HashMap<String, JsonWebKeyElements> keysMap = keys.stream().collect(new MapCollector<>(k -> k.getId(), k -> k));
        JsonWebKeyElements key1Response = keysMap.get("RsaKey1");
        JsonWebKeyElements key2Response = keysMap.get("RsaKey2");
        JsonWebKeyElements key3Response = keysMap.get("RsaKey3");

        byte[] bytes = "Text for testing of private/public key match".getBytes();
        RsaSigner rsaSigner = new RsaSigner(SIGNING_KEY_1);
        RsaVerifier rsaVerifier = new RsaVerifier(key1Response.getKey());
        rsaVerifier.verify(bytes, rsaSigner.sign(bytes));

        rsaSigner = new RsaSigner(SIGNING_KEY_2);
        rsaVerifier = new RsaVerifier(key2Response.getKey());
        rsaVerifier.verify(bytes, rsaSigner.sign(bytes));

        rsaSigner = new RsaSigner(SIGNING_KEY_3);
        rsaVerifier = new RsaVerifier(key3Response.getKey());
        rsaVerifier.verify(bytes, rsaSigner.sign(bytes));

        //ensure that none of the keys are padded
        keys.forEach(
          key ->
            assertFalse("Invalid padding for key:" + key.getKid(),
              key.getExponent().endsWith("=") ||
                key.getModulus().endsWith("="))
        );
    }

    @Test
    public void listResponseContainsAllKeysWhenAuthenticated() {
        Map<String, String> keysForUaaZone = new HashMap<>();
        keysForUaaZone.put("RsaKey1", SIGNING_KEY_1);
        keysForUaaZone.put("RsaKey2", SIGNING_KEY_2);
        keysForUaaZone.put("RsaKey3", SIGNING_KEY_3);
        keysForUaaZone.put("SymmetricKey", "ItHasSomeTextThatIsNotPEM");
        configureKeysForDefaultZone(keysForUaaZone);

        JsonWebKeySet keysResponse = tokenKeyEndpoint.getKeys(validUaaResource);
        List<JsonWebKeyElements> keys = keysResponse.getKeys();
        List<String> keyIds = keys.stream().map(JsonWebKeyElements::getId).collect(Collectors.toList());
        assertThat(keyIds, containsInAnyOrder("RsaKey1", "RsaKey2", "RsaKey3", "SymmetricKey"));

        JsonWebKeyElements symKeyResponse = keys.stream().filter(k -> k.getId().equals("SymmetricKey")).findAny().get();
        assertEquals("ItHasSomeTextThatIsNotPEM", symKeyResponse.getKey());
    }

    @Test
    public void tokenKeyEndpoint_ReturnsAllKeysForZone() {
        Map<String, String> keys = new HashMap<>();
        keys.put("key1", SIGNING_KEY_1);
        keys.put("key2", SIGNING_KEY_2);
        createAndSetTestZoneWithKeys(keys);

        JsonWebKeySet keysResponse = tokenKeyEndpoint.getKeys(mock(Principal.class));
        List<JsonWebKeyElements> keysForZone = keysResponse.getKeys();
        List<String> keyIds = keysForZone.stream().map(JsonWebKeyElements::getId).collect(Collectors.toList());
        assertThat(keyIds, containsInAnyOrder("key1", "key2"));
    }

    @Test
    public void responseHeaderIncludesEtag() {
        createAndSetTestZoneWithKeys(Collections.singletonMap("key1", SIGNING_KEY_1));

        ResponseEntity<JsonWebKeyElements> keyResponse = tokenKeyEndpoint.getKey(mock(Principal.class), "NaN");
        HttpHeaders headers = keyResponse.getHeaders();
        assertNotNull(headers.get("ETag"));

        ResponseEntity<JsonWebKeySet> keysResponse = tokenKeyEndpoint.getKeys(mock(Principal.class), "NaN");
        headers = keysResponse.getHeaders();
        assertNotNull(headers.get("ETag"));
    }

    @Test
    public void returns304IfUnmodified() {
        IdentityZone zone = createAndSetTestZoneWithKeys(null);

        String lastModified = String.valueOf(zone.getLastModified().getTime());

        ResponseEntity<JsonWebKeyElements> keyResponse = tokenKeyEndpoint.getKey(mock(Principal.class), lastModified);
        assertEquals(keyResponse.getStatusCode(), HttpStatus.NOT_MODIFIED);

        ResponseEntity<JsonWebKeySet> keysResponse = tokenKeyEndpoint.getKeys(mock(Principal.class), lastModified);
        assertEquals(keysResponse.getStatusCode(), HttpStatus.NOT_MODIFIED);
    }

    private IdentityZone createAndSetTestZoneWithKeys(Map<String, String> keys) {
        IdentityZone zone = MultitenancyFixture.identityZone("test-zone", "test");
        IdentityZoneConfiguration config = new IdentityZoneConfiguration();
        TokenPolicy tokenPolicy = new TokenPolicy();
        tokenPolicy.setKeys(keys);
        config.setTokenPolicy(tokenPolicy);
        zone.setConfig(config);
        IdentityZoneHolder.set(zone);

        return zone;
    }

    private void configureKeysForDefaultZone(Map<String, String> keys) {
        IdentityZoneProvisioning provisioning = mock(IdentityZoneProvisioning.class);
        IdentityZoneHolder.setProvisioning(provisioning);
        IdentityZone zone = IdentityZone.getUaa();
        IdentityZoneConfiguration config = new IdentityZoneConfiguration();
        TokenPolicy tokenPolicy = new TokenPolicy();
        tokenPolicy.setKeys(keys);
        config.setTokenPolicy(tokenPolicy);
        zone.setConfig(config);
        when(provisioning.retrieve("uaa")).thenReturn(zone);
    }
}
