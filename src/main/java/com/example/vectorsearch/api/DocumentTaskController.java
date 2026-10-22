package com.example.vectorsearch.api;

import com.example.vectorsearch.task.DocumentTasks;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** P3 document and task endpoints; search and invalidation are P4. */
@RestController
@RequestMapping("/api")
public class DocumentTaskController {
    private final DocumentTasks service;

    public DocumentTaskController(DocumentTasks service) { this.service = service; }

    @PostMapping("/documents")
    public ResponseEntity<SubmitResponse> submit(@Valid @RequestBody SubmitDocumentRequest request) {
        SubmitResponse created = service.submit(request);
        return ResponseEntity.accepted().location(URI.create(created.taskUrl())).body(created);
    }

    @GetMapping("/tasks/{taskId}")
    public TaskView task(@PathVariable String taskId) { return service.task(taskId); }

    @GetMapping("/documents")
    public DocumentPage documents(HttpServletRequest request) { return service.list(request.getParameterMap()); }

    @GetMapping("/documents/{documentId}")
    public DocumentDetail document(@PathVariable String documentId) { return service.document(documentId); }

    @GetMapping("/documents/{documentId}/task")
    public TaskView taskForDocument(@PathVariable String documentId) {
        return service.taskForDocument(documentId);
    }
}
