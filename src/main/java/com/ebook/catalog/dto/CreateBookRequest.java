package com.ebook.catalog.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
    @Size(max = 255, message = "Title must not exceed 255 characters")
    private String title;

    @Size(max = 5000, message = "Description must not exceed 5000 characters")
    private String description;

    @NotNull(message = "Price is required")
    @DecimalMin(value = "0.00", message = "Price must be zero or positive")
    private BigDecimal price;

    @DecimalMin(value = "0.00", message = "Discount must be zero or positive")
    private BigDecimal discount;

    @Size(max = 500, message = "Keywords must not exceed 500 characters")
    private String keywords;

    private Integer pages;

    private String coverUrl;

    private String previewUrl;

    private String bookUrl;

    private String versionNumber;

    @NotNull(message = "Category ID is required")
    private UUID categoryId;

    /**  discount cannot exceed price. Null discount is treated as zero. */
    @JsonIgnore
    @AssertTrue(message = "Discount must not exceed price")
    public boolean isDiscountWithinPrice() {
        if (price == null) return true; // @NotNull will flag it separately
        BigDecimal d = discount == null ? BigDecimal.ZERO : discount;
        return d.compareTo(price) <= 0;
    }
}
