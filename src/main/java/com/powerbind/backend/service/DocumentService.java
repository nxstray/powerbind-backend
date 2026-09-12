package com.powerbind.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

// Extracts plain text from uploaded documents (PDF, DOCX, TXT) for the AI agent
@Slf4j
@Service
public class DocumentService {

    private final Tika tika = new Tika();

    // Extract text content from a document file — returns empty string on failure
    public String extractText(MultipartFile file) {
        try {
            String text = tika.parseToString(file.getInputStream());
            // Cap length to avoid blowing up the context window
            int maxChars = 15000;
            if (text.length() > maxChars) {
                text = text.substring(0, maxChars) + "\n\n[... content truncated ...]";
            }
            return text.trim();
        } catch (Exception e) {
            log.error("[Document] Failed to extract text from {}: {}", file.getOriginalFilename(), e.getMessage());
            return "";
        }
    }

    // Check if the uploaded file is a document type (not image)
    public boolean isDocument(MultipartFile file) {
        String contentType = file.getContentType();
        if (contentType == null) return false;
        return contentType.equals("application/pdf")
                || contentType.contains("word")
                || contentType.equals("text/plain")
                || contentType.contains("document");
    }
}