package com.ebook.catalog.dto;

import lombok.*;
import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookApprovalLogResponse {
    private UUID id;
    private UUID bookId;
    private String bookTitle;
    private UUID senderId;
    private String senderEmail;
    private UUID receiverId;
    private String receiverEmail;
    private String action;
    private String message;
    private Instant createdAt;
}
