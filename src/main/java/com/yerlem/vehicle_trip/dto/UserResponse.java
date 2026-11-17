package com.yerlem.vehicle_trip.dto;

import com.yerlem.vehicle_trip.entity.User;
import com.yerlem.vehicle_trip.enums.UserStatus;

public record UserResponse(
        Long id,
        String fullName,
        String phone,
        UserStatus status,
        String profilePhotoUrl
) {
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getFullName(), user.getPhone(),
                user.getStatus(), user.getProfilePhotoUrl());
    }
}
