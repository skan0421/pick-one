package com.pickone.hide.dto;

import jakarta.validation.constraints.NotNull;

public record HideFromContactsRequest(@NotNull Boolean enabled) {
}
