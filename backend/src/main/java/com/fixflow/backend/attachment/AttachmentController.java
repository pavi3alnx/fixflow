package com.fixflow.backend.attachment;

import com.fixflow.backend.common.api.PageResponse;
import jakarta.validation.constraints.*;
import java.net.URI;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/maintenance-requests/{id}/attachments")
public class AttachmentController {
    private final AttachmentService service;
    public AttachmentController(AttachmentService service){this.service=service;}
    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AttachmentService.View> upload(@PathVariable @Positive long id,@RequestParam AttachmentPurpose purpose,@RequestPart("file") MultipartFile file) {
        var result=service.upload(id,purpose,file);return ResponseEntity.created(URI.create("/api/maintenance-requests/"+id+"/attachments/"+result.id()+"/download")).body(result);
    }
    @GetMapping public PageResponse<AttachmentService.View> list(@PathVariable @Positive long id,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size){return service.list(id,page,size);}
    @GetMapping("/{attachmentId}/download") public ResponseEntity<byte[]> download(@PathVariable @Positive long id,@PathVariable @Positive long attachmentId) {
        var file=service.download(id,attachmentId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType())).cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.filename()).build().toString())
                .header("X-Content-Type-Options","nosniff").body(file.bytes());
    }
}
