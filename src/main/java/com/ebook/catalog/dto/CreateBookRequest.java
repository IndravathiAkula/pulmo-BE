package com.ebook.catalog.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateBookRequest {

    @NotBlank(message = "Title is required")
    private String title;

    private String description;

    @NotNull(message = "Price is required")
    private BigDecimal price;

    private BigDecimal discount;

    private String keywords;

    private Integer pages;

    private String coverUrl;

    private String previewUrl;

    private String bookUrl;

    private String versionNumber;

    @NotNull(message = "Category ID is required")
    private UUID categoryId;
}
