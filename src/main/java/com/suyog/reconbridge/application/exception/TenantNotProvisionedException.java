package com.suyog.reconbridge.application.exception;

public class TenantNotProvisionedException extends RuntimeException {

    public TenantNotProvisionedException() {
        super("The authenticated tenant is not provisioned.");
    }
}