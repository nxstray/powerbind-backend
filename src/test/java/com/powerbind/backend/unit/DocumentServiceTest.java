package com.powerbind.backend.unit;

import com.powerbind.backend.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DisplayName("Unit Test (document)")
class DocumentServiceTest {

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService();
    }

    private MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }

    @Test
    @DisplayName("TC-UNIT-DOC-01 extractText returns the trimmed text content of a plain document")
    void extractText_shouldReturnTrimmedContent() {
        MockMultipartFile f = file("notes.txt", "text/plain",
                "\n  Ruang tamu menyala sejak pukul 19:00  \n\n".getBytes(StandardCharsets.UTF_8));

        String text = documentService.extractText(f);

        assertEquals("Ruang tamu menyala sejak pukul 19:00", text);
    }

    @Test
    @DisplayName("TC-UNIT-DOC-02 extractText caps long documents at 15000 chars and appends a truncation marker")
    void extractText_shouldTruncateLongContent() {
        byte[] big = "a".repeat(20000).getBytes(StandardCharsets.UTF_8);
        MockMultipartFile f = file("spec.txt", "text/plain", big);

        String text = documentService.extractText(f);

        String marker = "\n\n[... content truncated ...]";
        assertTrue(text.endsWith(marker), "truncation marker missing");
        assertEquals(15000 + marker.length(), text.length());
        assertEquals("a".repeat(15000), text.substring(0, 15000));
    }

    @Test
    @DisplayName("TC-UNIT-DOC-03 extractText returns empty string when the stream cannot be read")
    void extractText_shouldReturnEmpty_whenStreamFails() throws IOException {
        MultipartFile broken = mock(MultipartFile.class);
        when(broken.getOriginalFilename()).thenReturn("broken.pdf");
        when(broken.getInputStream()).thenThrow(new IOException("stream closed"));

        String text = documentService.extractText(broken);

        assertEquals("", text);
    }

    @Test
    @DisplayName("TC-UNIT-DOC-04 extractText returns empty string for an empty file")
    void extractText_shouldReturnEmpty_whenFileEmpty() {
        MockMultipartFile f = file("empty.txt", "text/plain", new byte[0]);

        assertEquals("", documentService.extractText(f));
    }

    @Test
    @DisplayName("TC-UNIT-DOC-05 isDocument accepts pdf, word, plain text, and document content types")
    void isDocument_shouldAcceptDocumentTypes() {
        assertTrue(documentService.isDocument(file("a.pdf", "application/pdf", new byte[0])));
        assertTrue(documentService.isDocument(
                file("b.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", new byte[0])));
        assertTrue(documentService.isDocument(file("c.txt", "text/plain", new byte[0])));
        assertTrue(documentService.isDocument(file("d.bin", "application/x-custom-document", new byte[0])));
    }

    @Test
    @DisplayName("TC-UNIT-DOC-06 isDocument rejects images and files without a content type")
    void isDocument_shouldRejectNonDocuments() {
        assertFalse(documentService.isDocument(file("photo.png", "image/png", new byte[0])));
        assertFalse(documentService.isDocument(file("video.mp4", "video/mp4", new byte[0])));
        assertFalse(documentService.isDocument(file("unknown.bin", null, new byte[0])));
    }
}
