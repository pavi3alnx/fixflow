package com.fixflow.backend;

import com.fixflow.backend.attachment.*;
import com.fixflow.backend.identity.*;
import com.fixflow.backend.location.*;
import com.fixflow.backend.request.*;
import com.fixflow.backend.notification.*;
import com.fixflow.backend.security.JwtTokens;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CommunicationIntegrationTests {
    @Test void readStateHandlesDatabaseClockSkewAndWithholdsLegacyFreeText()throws Exception{
        long first=jdbc.queryForObject("insert into notifications(recipient_id,request_id,event_type,title,message,created_at) values (?,?,'LEGACY','Private legacy title','private-legacy-payload',current_timestamp+interval '1 day') returning id",Long.class,admin.getId(),id);
        jdbc.update("insert into notifications(recipient_id,request_id,event_type,title,message,created_at) values (?,?,'LEGACY','Legacy','Legacy',current_timestamp+interval '2 days')",admin.getId(),id);
        String response=read("/api/notifications",a).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertFalse(response.contains("private-legacy-payload"));assertFalse(response.contains("Private legacy title"));
        patchJson("/api/notifications/"+first+"/read",Map.of(),a).andExpect(status().isNoContent());
        patchJson("/api/notifications/read-all",Map.of(),a).andExpect(status().isNoContent());
        assertEquals(0,jdbc.queryForObject("select count(*) from notifications where recipient_id=? and (read_at is null or read_at<created_at)",Integer.class,admin.getId()));
    }
    @Test void resolutionConfirmationAndClosureNotifyRelevantParticipantsOnly()throws Exception{
        started();patchJson(techPath()+"/resolve",Map.of("expectedUpdatedAt",stamp(),"repairNotes","Repaired and verified"),t).andExpect(status().isOk());
        assertEquals(1,notifications(reporter.getId(),"STATUS_RESOLVED"));assertEquals(1,notifications(admin.getId(),"STATUS_RESOLVED"));assertEquals(0,notifications(technician.getId(),"STATUS_RESOLVED"));
        patchJson(path()+"/confirmation",Map.of("expectedUpdatedAt",stamp()),r).andExpect(status().isOk());assertEquals(1,notifications(admin.getId(),"STATUS_CONFIRMED"));assertEquals(0,notifications(reporter.getId(),"STATUS_CONFIRMED"));
        patchJson(adminPath()+"/status",Map.of("status","CLOSED","expectedUpdatedAt",stamp()),a).andExpect(status().isOk());assertEquals(1,notifications(reporter.getId(),"STATUS_CLOSED"));assertEquals(0,notifications(technician.getId(),"STATUS_CLOSED"));
    }
    private static final TestDatabase DATABASE=database();
    private static final Path ROOT=directory();
    private static TestDatabase database(){try{return new TestDatabase();}catch(java.sql.SQLException e){throw new ExceptionInInitializerError(e);}}
    private static Path directory(){try{return Files.createTempDirectory("fixflow-ff09-");}catch(IOException e){throw new ExceptionInInitializerError(e);}}
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry){
        registry.add("spring.datasource.url",()->DATABASE.url);registry.add("spring.datasource.username",()->DATABASE.username);registry.add("spring.datasource.password",()->DATABASE.password);
        registry.add("fixflow.photos.local-root",ROOT::toString);registry.add("fixflow.photos.max-bytes",()->4096);registry.add("fixflow.photos.max-files",()->3);registry.add("fixflow.photos.max-dimension",()->64);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired BuildingRepository buildings;
    @Autowired JwtTokens tokens;
    @Autowired JdbcTemplate jdbc;
    @Autowired MaintenanceRequestRepository requests;
    @Autowired NotificationPublisher publisher;
    @Autowired AttachmentService attachments;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean PhotoStorage storage;
    @Test void indeterminateCommitOutcomePreservesContentForReconciliation()throws Exception{
        byte[] bytes=image(8,"png");long before=files();
        var context=org.springframework.security.core.context.SecurityContextHolder.getContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(reporter.getId().toString(),null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_REPORTER"))));
        try {
            new TransactionTemplate(transactions).executeWithoutResult(status->{
                attachments.upload(id,AttachmentPurpose.ISSUE_PHOTO,new MockMultipartFile("file","photo.png","image/png",bytes));
                var callbacks=org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations().stream()
                        .filter(callback->callback.getClass().getName().startsWith(AttachmentService.class.getName()+"$")).toList();
                assertEquals(1,callbacks.size());callbacks.get(0).afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_UNKNOWN);
                verify(storage,never()).delete(anyString());
            });
        } finally {org.springframework.security.core.context.SecurityContextHolder.clearContext();}
        assertEquals(before+1,files());assertEquals(1,jdbc.queryForObject("select count(*) from attachments where request_id=?",Integer.class,id));
    }
    private User admin,reporter,other,technician,replacement;
    private String a,r,o,t,next;
    private long id,buildingId;
    @BeforeEach void fixture() throws Exception {
        admin=user("ADMIN");reporter=user("REPORTER");other=user("REPORTER");technician=user("TECHNICIAN");replacement=user("TECHNICIAN");
        a=tokens.issue(admin).accessToken();r=tokens.issue(reporter).accessToken();o=tokens.issue(other).accessToken();t=tokens.issue(technician).accessToken();next=tokens.issue(replacement).accessToken();
        var building=new Building();building.setCode("COMM-"+UUID.randomUUID());building.setName("Communication test");buildingId=buildings.saveAndFlush(building).getId();
        id=body(postJson("/api/maintenance-requests",Map.of("title","Private title "+UUID.randomUUID(),"description","Private request text","buildingId",buildingId,"categoryId",1,"suggestedPriority","HIGH"),r)
                .andExpect(status().isCreated())).get("id").asLong();
    }
    private User user(String role){var u=new User();u.setFullName("Communication "+role);u.setEmail(UUID.randomUUID()+"@example.invalid");u.setStudentStaffId(UUID.randomUUID().toString());u.setDepartment("Testing");u.setPasswordHash("synthetic-unused");u.setRole(roles.findByName(role).orElseThrow());u.setEnabled(true);u.setEmailVerified(true);return users.saveAndFlush(u);}
    private ResultActions postJson(String path,Object input,String token)throws Exception{return mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(input)));}
    private ResultActions patchJson(String path,Object input,String token)throws Exception{return mvc.perform(patch(path).header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(input)));}
    private ResultActions read(String path,String token)throws Exception{return mvc.perform(get(path).header("Authorization","Bearer "+token));}
    private JsonNode body(ResultActions result)throws Exception{return json.readTree(result.andReturn().getResponse().getContentAsString());}
    private String path(){return "/api/maintenance-requests/"+id;}
    private String adminPath(){return "/api/admin/maintenance-requests/"+id;}
    private String techPath(){return "/api/technician/maintenance-requests/"+id;}
    private String stamp()throws Exception{return body(read(path(),a).andExpect(status().isOk())).get("updatedAt").asText();}
    private void assigned()throws Exception{
        patchJson(adminPath()+"/verification",Map.of("status","VERIFIED","expectedUpdatedAt",stamp()),a).andExpect(status().isOk());
        postJson(adminPath()+"/assignments",Map.of("technicianId",technician.getId(),"expectedUpdatedAt",stamp()),a).andExpect(status().isCreated());
    }
    private void started()throws Exception{assigned();patchJson(techPath()+"/accept",Map.of("expectedUpdatedAt",stamp()),t).andExpect(status().isOk());patchJson(techPath()+"/start",Map.of("expectedUpdatedAt",stamp()),t).andExpect(status().isOk());}
    private byte[] image(int size,String format)throws IOException{var image=new BufferedImage(size,size,BufferedImage.TYPE_INT_RGB);var output=new ByteArrayOutputStream();ImageIO.write(image,format,output);image.flush();return output.toByteArray();}
    private ResultActions upload(byte[] bytes,String filename,String mime,String purpose,String token)throws Exception{return mvc.perform(multipart(path()+"/attachments").file(new MockMultipartFile("file",filename,mime,bytes)).param("purpose",purpose).header("Authorization","Bearer "+token));}
    private long notifications(long recipient,String event){return jdbc.queryForObject("select count(*) from notifications where request_id=? and recipient_id=? and event_type=?",Long.class,id,recipient,event);}
    private long countNotifications(){return jdbc.queryForObject("select count(*) from notifications where request_id=?",Long.class,id);}
    private long files()throws IOException{try(var paths=Files.list(ROOT)){return paths.count();}}

    @Test void publicPlainTextCommentsUseServerAuthorAndLeaveRequestTokenUnchanged()throws Exception{
        String before=stamp();String content="<script>alert('plain text')</script>";
        postJson(path()+"/comments",Map.of("body"," "+content+" "),r).andExpect(status().isCreated()).andExpect(jsonPath("authorId").value(reporter.getId())).andExpect(jsonPath("authorRole").value("REPORTER")).andExpect(jsonPath("body").value(content));
        postJson(path()+"/comments",Map.of("body","Administrator reply"),a).andExpect(status().isCreated());
        read(path()+"/comments?size=1&page=1",r).andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(2)).andExpect(jsonPath("content[0].body").value("Administrator reply"));
        assertEquals(before,stamp());assertEquals(1,notifications(admin.getId(),"COMMENT"));assertEquals(1,notifications(reporter.getId(),"COMMENT"));
    }
    @Test void spoofedAuthorsAndInternalCommentRequestsAreRejectedRatherThanPublished()throws Exception{
        for(var input:List.of(Map.of("body","Private comment","internal",true),Map.of("body","Private comment","visibility","INTERNAL"),Map.of("body","Spoofed","authorId",admin.getId())))
            postJson(path()+"/comments",input,r).andExpect(status().isBadRequest());
        assertEquals(0,jdbc.queryForObject("select count(*) from comments where request_id=?",Integer.class,id));
    }
    @Test void commentsValidateContentPaginationAndTerminalReadOnlyPolicy()throws Exception{
        for(String text:List.of(" ","x".repeat(5001)))postJson(path()+"/comments",Map.of("body",text),r).andExpect(status().isBadRequest());
        read(path()+"/comments?size=101",r).andExpect(status().isBadRequest());read(path()+"/comments?page=-1",r).andExpect(status().isBadRequest());
        jdbc.update("update maintenance_requests set status='CLOSED' where id=?",id);postJson(path()+"/comments",Map.of("body","Late comment"),r).andExpect(status().isConflict());read(path()+"/comments",r).andExpect(status().isOk());
    }
    @Test void commentOwnershipAndReassignmentRestrictionsAreEnforced()throws Exception{
        read(path()+"/comments",o).andExpect(status().isNotFound());postJson(path()+"/comments",Map.of("body","Intrusion"),o).andExpect(status().isNotFound());
        assigned();postJson(path()+"/comments",Map.of("body","Technician reply"),t).andExpect(status().isCreated());
        patchJson(adminPath()+"/assignment",Map.of("technicianId",replacement.getId(),"reason","Specialist needed","expectedUpdatedAt",stamp()),a).andExpect(status().isOk());
        read(path()+"/comments",t).andExpect(status().isNotFound());postJson(path()+"/comments",Map.of("body","Former technician"),t).andExpect(status().isNotFound());read(path()+"/comments",next).andExpect(status().isOk());
    }
    @Test void imageUploadNormalizesContentAndSecureDownloadDoesNotExposeStorage()throws Exception{
        byte[] bytes=image(10,"png");var polyglot=new ByteArrayOutputStream();polyglot.write(bytes);polyglot.write("<script>discard me</script>".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String before=stamp();var dto=body(upload(polyglot.toByteArray(),"../../unsafe\r\nname.png","image/png","ISSUE_PHOTO",r).andExpect(status().isCreated()));long photo=dto.get("id").asLong();
        assertFalse(dto.has("storageKey"));assertFalse(dto.has("storageProvider"));assertFalse(dto.get("filename").asText().contains("/"));assertEquals(before,stamp());
        var result=read(path()+"/attachments/"+photo+"/download",r).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options","nosniff")).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
        assertNotNull(ImageIO.read(new ByteArrayInputStream(result.getContentAsByteArray())));assertFalse(new String(result.getContentAsByteArray(),java.nio.charset.StandardCharsets.ISO_8859_1).contains("discard me"));
        assertEquals("attachment; filename=\"photo-"+photo+".png\"",result.getHeader("Content-Disposition"));
        read(path()+"/attachments",a).andExpect(jsonPath("totalElements").value(1));
    }
    @Test void invalidAndOversizedImagesAndMimeSpoofingAreRejected()throws Exception{
        upload(new byte[0],"empty.png","image/png","ISSUE_PHOTO",r).andExpect(status().isUnsupportedMediaType());
        upload("<svg/>".getBytes(),"photo.svg","image/svg+xml","ISSUE_PHOTO",r).andExpect(status().isUnsupportedMediaType());
        upload("MZ executable".getBytes(),"photo.png","image/png","ISSUE_PHOTO",r).andExpect(status().isUnsupportedMediaType());
        upload(new byte[]{(byte)137,80,78,71,13,10,26,10},"bad.png","image/png","ISSUE_PHOTO",r).andExpect(status().isUnsupportedMediaType());
        upload(image(10,"png"),"fake.jpg","image/jpeg","ISSUE_PHOTO",r).andExpect(status().isUnsupportedMediaType());
        upload(new byte[4097],"large.png","image/png","ISSUE_PHOTO",r).andExpect(status().isPayloadTooLarge());
        upload(image(65,"png"),"dimensions.png","image/png","ISSUE_PHOTO",r).andExpect(status().isPayloadTooLarge());
        upload(image(10,"jpeg"),"valid.jpeg","image/jpeg","ISSUE_PHOTO",r).andExpect(status().isCreated());
    }
    @Test void photoCountIsBoundedAndAuthorizationIncludesRequestAssociation()throws Exception{
        long photo=body(upload(image(8,"png"),"one.png","image/png","ISSUE_PHOTO",r).andExpect(status().isCreated())).get("id").asLong();
        for(int i=0;i<2;i++)upload(image(8,"png"),"same.png","image/png","ISSUE_PHOTO",r).andExpect(status().isCreated());
        upload(image(8,"png"),"four.png","image/png","ISSUE_PHOTO",r).andExpect(status().isConflict());
        read(path()+"/attachments",o).andExpect(status().isNotFound());read(path()+"/attachments/"+photo+"/download",o).andExpect(status().isNotFound());
        long ownOther=body(postJson("/api/maintenance-requests",Map.of("title","Second","description","Details","buildingId",buildingId,"categoryId",1,"suggestedPriority","LOW"),r).andExpect(status().isCreated())).get("id").asLong();
        read("/api/maintenance-requests/"+ownOther+"/attachments/"+photo+"/download",r).andExpect(status().isNotFound());
        upload(image(8,"png"),"invalid.png","image/png","REPAIR_EVIDENCE",r).andExpect(status().isForbidden());
        upload(image(8,"png"),"invalid.png","image/png","ISSUE_PHOTO",a).andExpect(status().isForbidden());
    }
    @Test void storageFailureAndDatabaseRollbackLeaveNoMetadataOrOrphanFile()throws Exception{
        long count=files();doThrow(PhotoFailure.storage()).when(storage).put(anyString(),any(byte[].class),anyString());
        upload(image(8,"png"),"fail.png","image/png","ISSUE_PHOTO",r).andExpect(status().isServiceUnavailable());assertEquals(count,files());
        doCallRealMethod().when(storage).put(anyString(),any(byte[].class),anyString());
        jdbc.execute("create function ff09_fail_attachment() returns trigger language plpgsql as $$ begin if NEW.request_id="+id+" then raise exception 'synthetic failure' using errcode='23514';end if;return NEW;end $$");
        jdbc.execute("create trigger ff09_fail_attachment before insert on attachments for each row execute function ff09_fail_attachment()");
        try {upload(image(8,"png"),"rollback.png","image/png","ISSUE_PHOTO",r).andExpect(status().isConflict());assertEquals(count,files());assertEquals(0,jdbc.queryForObject("select count(*) from attachments where request_id=?",Integer.class,id));}
        finally {jdbc.execute("drop trigger ff09_fail_attachment on attachments");jdbc.execute("drop function ff09_fail_attachment()");}
    }
    @Test void repairUploadsBindToEpisodeAndIntegrateWithResolutionAndReopening()throws Exception{
        started();long episode=jdbc.queryForObject("select id from assignments where request_id=? and ended_at is null",Long.class,id);
        var photo=body(upload(image(8,"png"),"repair.png","image/png","REPAIR_EVIDENCE",t).andExpect(status().isCreated()));assertEquals(episode,photo.get("assignmentId").asLong());long attachment=photo.get("id").asLong();
        patchJson(techPath()+"/resolve",Map.of("expectedUpdatedAt",stamp(),"repairNotes","Repair tested successfully","evidenceAttachmentIds",List.of(attachment)),t).andExpect(status().isOk());
        assertEquals(attachment,jdbc.queryForObject("select attachment_id from assignment_evidence where assignment_id=?",Long.class,episode));
        read(path()+"/attachments/"+attachment+"/download",t).andExpect(status().isNotFound());read(path()+"/attachments/"+attachment+"/download",r).andExpect(status().isOk());
        patchJson(path()+"/reopening",Map.of("expectedUpdatedAt",stamp(),"notes","Problem recurred after repair"),r).andExpect(status().isOk());
        patchJson(adminPath()+"/status",Map.of("status","VERIFIED","expectedUpdatedAt",stamp()),a).andExpect(status().isOk());postJson(adminPath()+"/assignments",Map.of("technicianId",technician.getId(),"expectedUpdatedAt",stamp()),a).andExpect(status().isCreated());
        patchJson(techPath()+"/accept",Map.of("expectedUpdatedAt",stamp()),t).andExpect(status().isOk());patchJson(techPath()+"/start",Map.of("expectedUpdatedAt",stamp()),t).andExpect(status().isOk());
        patchJson(techPath()+"/resolve",Map.of("expectedUpdatedAt",stamp(),"repairNotes","New cycle repair","evidenceAttachmentIds",List.of(attachment)),t).andExpect(status().isBadRequest());
    }
    @Test void submissionAssignmentStatusAndUrgencyNotifyRelevantUsersWithoutPrivateContent()throws Exception{
        assertEquals(1,notifications(admin.getId(),"REQUEST_SUBMITTED"));assertEquals(0,notifications(reporter.getId(),"REQUEST_SUBMITTED"));assertEquals(0,notifications(other.getId(),"REQUEST_SUBMITTED"));
        assigned();assertEquals(1,notifications(technician.getId(),"ASSIGNED"));assertEquals(1,notifications(reporter.getId(),"ASSIGNED"));
        patchJson(adminPath()+"/priority",Map.of("priority","URGENT","expectedUpdatedAt",stamp()),a).andExpect(status().isOk());assertEquals(1,notifications(technician.getId(),"URGENT_PRIORITY"));
        String output=read("/api/notifications?size=100",t).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();assertFalse(output.contains("Private title"));assertFalse(output.contains("Private request text"));
    }
    @Test void notificationReadsAreRecipientOnlyUnreadAndIdempotent()throws Exception{
        long notification=jdbc.queryForObject("select id from notifications where request_id=? and recipient_id=? and event_type='REQUEST_SUBMITTED'",Long.class,id,admin.getId());
        patchJson("/api/notifications/"+notification+"/read",Map.of(),r).andExpect(status().isNotFound());
        read("/api/notifications/unread-count",a).andExpect(jsonPath("unreadCount").value(1));
        for(int i=0;i<2;i++)patchJson("/api/notifications/"+notification+"/read",Map.of(),a).andExpect(status().isNoContent());
        read("/api/notifications?unread=true",a).andExpect(jsonPath("totalElements").value(0));read("/api/notifications/unread-count",a).andExpect(jsonPath("unreadCount").value(0));
        postJson(path()+"/comments",Map.of("body","New comment"),r).andExpect(status().isCreated());patchJson("/api/notifications/read-all",Map.of(),a).andExpect(status().isNoContent());patchJson("/api/notifications/read-all",Map.of(),a).andExpect(status().isNoContent());
        read("/api/notifications/unread-count",a).andExpect(jsonPath("unreadCount").value(0));read("/api/notifications?size=101",a).andExpect(status().isBadRequest());
    }
    @Test void historicalNotificationsCannotBypassCurrentRequestVisibilityAfterReassignment()throws Exception{
        assigned();patchJson(adminPath()+"/assignment",Map.of("technicianId",replacement.getId(),"reason","Reassignment needed","expectedUpdatedAt",stamp()),a).andExpect(status().isOk());
        read("/api/notifications",t).andExpect(status().isOk()).andExpect(jsonPath("content[0].requestAccessible").value(false)).andExpect(jsonPath("content[0].requestId").isEmpty());read(path(),t).andExpect(status().isNotFound());
    }
    @Test void eventKeyUniquenessNoOpsAndRejectedWritesDoNotDuplicateNotifications()throws Exception{
        long before=countNotifications();patchJson(adminPath()+"/priority",Map.of("priority","MEDIUM","expectedUpdatedAt",stamp()),a).andExpect(status().isOk());assertEquals(before,countNotifications());
        patchJson(adminPath()+"/verification",Map.of("status","ASSIGNED","expectedUpdatedAt",stamp()),a).andExpect(status().isBadRequest());assertEquals(before,countNotifications());
        new TransactionTemplate(transactions).executeWithoutResult(status->{var request=requests.findById(id).orElseThrow();publisher.publish(request,"COMMENT","test-event",reporter.getId());publisher.publish(request,"COMMENT","test-event",reporter.getId());});
        assertEquals(1,jdbc.queryForObject("select count(*) from notifications where recipient_id=? and event_key='test-event'",Integer.class,admin.getId()));
    }
    @Test void notificationFailureRollsBackCommentAndWorkflowHistoryTogether()throws Exception{
        jdbc.execute("create function ff09_fail_notification() returns trigger language plpgsql as $$ begin if NEW.request_id="+id+" then raise exception 'synthetic notification failure' using errcode='23514';end if;return NEW;end $$");
        jdbc.execute("create trigger ff09_fail_notification before insert on notifications for each row execute function ff09_fail_notification()");
        String before=stamp();long count=countNotifications();
        try {
            postJson(path()+"/comments",Map.of("body","Must roll back"),r).andExpect(status().isConflict());assertEquals(0,jdbc.queryForObject("select count(*) from comments where request_id=?",Integer.class,id));
            // A different administrator causes a reporter notification, exercising required workflow persistence.
            patchJson(adminPath()+"/verification",Map.of("status","VERIFIED","expectedUpdatedAt",before),a).andExpect(status().isConflict());
            assertEquals(before,stamp());assertEquals("OPEN",body(read(path(),a)).get("status").asText());assertEquals(1,jdbc.queryForObject("select count(*) from status_history where request_id=?",Integer.class,id));assertEquals(count,countNotifications());
        }finally{jdbc.execute("drop trigger ff09_fail_notification on notifications");jdbc.execute("drop function ff09_fail_notification()");}
    }
    @Test void reassignmentRacesCannotAuthorizeFormerTechnicianWrites()throws Exception{
        assigned();String version=stamp();
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);
            var comment=pool.submit(()->{start.await();return postJson(path()+"/comments",Map.of("body","Concurrent work note"),t).andReturn().getResponse().getStatus();});
            var reassign=pool.submit(()->{start.await();return patchJson(adminPath()+"/assignment",Map.of("technicianId",replacement.getId(),"reason","Concurrent reassignment","expectedUpdatedAt",version),a).andReturn().getResponse().getStatus();});
            start.countDown();assertTrue(List.of(201,404).contains(comment.get(15,TimeUnit.SECONDS)));assertEquals(200,reassign.get(15,TimeUnit.SECONDS));
        }
        upload(image(8,"png"),"old.png","image/png","REPAIR_EVIDENCE",t).andExpect(status().isNotFound());
        postJson(path()+"/comments",Map.of("body","Too late"),t).andExpect(status().isNotFound());
    }
    @Test void uploadVersusReassignmentUsesTheSameRequestLock()throws Exception{
        assigned();String version=stamp();byte[] bytes=image(8,"png");
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);
            var photo=pool.submit(()->{start.await();return upload(bytes,"concurrent.png","image/png","REPAIR_EVIDENCE",t).andReturn().getResponse().getStatus();});
            var reassign=pool.submit(()->{start.await();return patchJson(adminPath()+"/assignment",Map.of("technicianId",replacement.getId(),"reason","Reassignment required","expectedUpdatedAt",version),a).andReturn().getResponse().getStatus();});
            start.countDown();int outcome=photo.get(15,TimeUnit.SECONDS);assertTrue(List.of(201,404).contains(outcome));assertEquals(200,reassign.get(15,TimeUnit.SECONDS));
            assertEquals(outcome==201?1:0,jdbc.queryForObject("select count(*) from attachments where request_id=?",Integer.class,id));
        }
    }
    @Test void inactiveAndUnauthenticatedAccountsCannotUseCommunicationEndpoints()throws Exception{
        mvc.perform(get(path()+"/comments")).andExpect(status().isUnauthorized());mvc.perform(get("/api/notifications")).andExpect(status().isUnauthorized());
        reporter.setEnabled(false);reporter.setTokenVersion(1);users.saveAndFlush(reporter);postJson(path()+"/comments",Map.of("body","Revoked"),r).andExpect(status().isUnauthorized());read(path()+"/attachments",r).andExpect(status().isUnauthorized());
        mvc.perform(delete(path()+"/comments/1").header("Authorization","Bearer "+a)).andExpect(status().isForbidden());
    }
}
