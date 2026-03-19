// CreateUserRequest.java  (для ADMIN)
package com.e.demo.dto;

public record CreateUserRequest(String email, String password, String fullName, String role) {}
