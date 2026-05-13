package com.ebook.admin.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RejectBookRequest {
    @Size(max = 1000, message = "Reason must not exceed 1000 characters")
    private String reason;
}
