package org.cloudfoundry.identity.uaa.account;

import lombok.Data;
import org.cloudfoundry.identity.uaa.codestore.ExpiringCode;

@Data
public class ForgotPasswordInfo {
    private final String userId;
    private final String email;
    private final ExpiringCode resetPasswordCode;
}
