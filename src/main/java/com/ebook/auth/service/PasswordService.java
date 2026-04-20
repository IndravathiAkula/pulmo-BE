package com.ebook.auth.service;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PasswordService {

    private final Argon2 argon2;

    public PasswordService() {
        this.argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id, 16, 32);
    }

    public String hashPassword(String password) {
        return argon2.hash(2, 65536, 1, password.toCharArray());
    }

    public boolean verifyPassword(String hash, String password) {
        return argon2.verify(hash, password.toCharArray());
    }
}
