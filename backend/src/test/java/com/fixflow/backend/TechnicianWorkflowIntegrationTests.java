package com.fixflow.backend;

import com.fixflow.backend.identity.*;
import com.fixflow.backend.location.*;
import com.fixflow.backend.request.*;
import com.fixflow.backend.assignment.*;
import com.fixflow.backend.security.JwtTokens;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TechnicianWorkflowIntegrationTests {
    private static final TestDatabase DATABASE=database();
    private static TestDatabase database() {
        try { return new TestDatabase(); } catch(java.sql.SQLException e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->DATABASE.url);registry.add("spring.datasource.username",()->DATABASE.username);registry.add("spring.datasource.password",()->DATABASE.password);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired BuildingRepository buildings;
    @Autowired JwtTokens tokens;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean JavaMailSender sender;
    private User admin,reporter,otherReporter,tech,otherTech;
    private String a,r,otherR,t,otherT;
    private long id,buildingId;
    private final Queue<SimpleMailMessage> mail=new ConcurrentLinkedQueue<>();
    @BeforeEach void fixture() throws Exception {
        doAnswer(inv->{mail.add(inv.getArgument(0));return null;}).when(sender).send(any(SimpleMailMessage.class));
        admin=account("ADMIN");reporter=account("REPORTER");otherReporter=account("REPORTER");tech=account("TECHNICIAN");otherTech=account("TECHNICIAN");
        a=tokens.issue(admin).accessToken();r=tokens.issue(reporter).accessToken();otherR=tokens.issue(otherReporter).accessToken();t=tokens.issue(tech).accessToken();otherT=tokens.issue(otherTech).accessToken();
        var building=new Building();building.setCode("FF08-"+UUID.randomUUID());building.setName("Workflow building");buildingId=buildings.saveAndFlush(building).getId();
        id=body(postJson("/api/maintenance-requests",Map.of("title","Work "+UUID.randomUUID(),"description","Fault","buildingId",buildingId,"categoryId",1,"suggestedPriority","HIGH"),r)
                .andExpect(status().isCreated())).get("id").asLong();
    }
    private User account(String role) {
        var user=new User();user.setFullName("Workflow "+role);user.setEmail(UUID.randomUUID()+"@example.invalid");user.setStudentStaffId(UUID.randomUUID().toString());user.setDepartment("Test");
        user.setPasswordHash("test-only-unused");user.setRole(roles.findByName(role).orElseThrow());user.setEmailVerified(true);user.setEnabled(true);return users.saveAndFlush(user);
    }
    private ResultActions postJson(String path,Object value,String token) throws Exception { return mvc.perform(post(path).header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(value))); }
    private ResultActions patchJson(String path,Object value,String token) throws Exception { return mvc.perform(patch(path).header("Authorization","Bearer "+token).contentType("application/json").content(json.writeValueAsString(value))); }
    private ResultActions read(String path,String token) throws Exception { return mvc.perform(get(path).header("Authorization","Bearer "+token)); }
    private JsonNode body(ResultActions response) throws Exception { return json.readTree(response.andReturn().getResponse().getContentAsString()); }
    private String publicPath() { return "/api/maintenance-requests/"+id; }
    private String adminPath() { return "/api/admin/maintenance-requests/"+id; }
    private String techPath() { return "/api/technician/maintenance-requests/"+id; }
    private String stamp() throws Exception { return body(read(publicPath(),a).andExpect(status().isOk())).get("updatedAt").asText(); }
    private Map<String,Object> action(String notes) throws Exception { var data=new HashMap<String,Object>();data.put("expectedUpdatedAt",stamp());if(notes!=null)data.put("notes",notes);return data; }
    private void verifyRequest() throws Exception { patchJson(adminPath()+"/verification",Map.of("status","VERIFIED","expectedUpdatedAt",stamp()),a).andExpect(status().isOk()); }
    private ResultActions assign(long technician,boolean replace,String expected) throws Exception {
        var data=new HashMap<String,Object>(Map.of("technicianId",technician,"expectedUpdatedAt",expected));if(replace)data.put("reason","Replacement required");
        return replace?patchJson(adminPath()+"/assignment",data,a):postJson(adminPath()+"/assignments",data,a);
    }
    private void assigned() throws Exception { verifyRequest();assign(tech.getId(),false,stamp()).andExpect(status().isCreated()); }
    private void started() throws Exception { assigned();patchJson(techPath()+"/accept",action(null),t).andExpect(status().isOk());patchJson(techPath()+"/start",action(null),t).andExpect(status().isOk()); }
    private ResultActions resolve(String token,List<Long> evidence) throws Exception { return patchJson(techPath()+"/resolve",Map.of("expectedUpdatedAt",stamp(),"repairNotes","Replaced damaged component and tested","evidenceAttachmentIds",evidence),token); }
    private long activeAssignment() { return jdbc.queryForObject("select id from assignments where request_id=? and ended_at is null",Long.class,id); }
    private long historyCount() { return jdbc.queryForObject("select count(*) from status_history where request_id=?",Long.class,id); }
    private Map<String,Object> registration(String email) { return new HashMap<>(Map.of("fullName","New Technician","email",email,"studentStaffId",UUID.randomUUID().toString(),"department","Facilities","password","Safe-password-123!")); }
    private String otp(String email) {
        var msg=mail.stream().filter(m->Arrays.asList(m.getTo()).contains(email)).reduce((first,last)->last).orElseThrow();var match=Pattern.compile("\\b[0-9]{6}\\b").matcher(msg.getText());assertTrue(match.find());return match.group();
    }
    private ResultActions profileUpdate(long userId,boolean enabled,boolean suspended,String expected) throws Exception {
        return mvc.perform(put("/api/admin/technicians/"+userId).header("Authorization","Bearer "+a).contentType("application/json")
                .content(json.writeValueAsString(Map.of("fullName","Updated Technician","department","Facilities","enabled",enabled,"administrativelyDisabled",suspended,"expectedUpdatedAt",expected))));
    }
    private String profileStamp(long userId) throws Exception { return body(read("/api/admin/technicians/"+userId,a).andExpect(status().isOk())).get("updatedAt").asText(); }

    @Test void secureTechnicianProvisioningReusesOtpAndPasswordAuthentication() throws Exception {
        String email=UUID.randomUUID()+"@university.example";var input=registration(email.toUpperCase(Locale.ROOT));input.put("role","ADMIN");input.put("enabled",true);input.put("emailVerified",true);
        var profile=body(postJson("/api/admin/technicians",input,a).andExpect(status().isCreated()).andExpect(jsonPath("enabled").value(false)).andExpect(jsonPath("emailVerified").value(false)));
        var user=users.findByEmail(email).orElseThrow();assertEquals("TECHNICIAN",user.getRole().getName());assertTrue(user.getPasswordHash().startsWith("$2"));assertFalse(profile.has("passwordHash"));
        postJson("/api/auth/login",Map.of("email",email,"password","Safe-password-123!"),a).andExpect(status().isUnauthorized());
        postJson("/api/auth/verify-email",Map.of("email",email,"otp",otp(email)),a).andExpect(status().isOk());
        postJson("/api/auth/login",Map.of("email",email,"password","Safe-password-123!"),a).andExpect(status().isOk()).andExpect(jsonPath("user.role").value("TECHNICIAN"));
        assertNotNull(profile.get("id"));
    }
    @Test void duplicateInvalidAndUnauthorizedProvisioningIsRejected() throws Exception {
        String email=UUID.randomUUID()+"@university.example";var data=registration(email);
        postJson("/api/admin/technicians",data,a).andExpect(status().isCreated());postJson("/api/admin/technicians",data,a).andExpect(status().isConflict());
        data.put("email",UUID.randomUUID()+"@outside.example");postJson("/api/admin/technicians",data,a).andExpect(status().isBadRequest());
        data.put("fullName"," ");postJson("/api/admin/technicians",data,a).andExpect(status().isBadRequest());
        for(String token:List.of(r,t)) postJson("/api/admin/technicians",registration(email),token).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/technicians").contentType("application/json").content(json.writeValueAsString(registration(email)))).andExpect(status().isUnauthorized());
    }
    @Test void pendingSuspensionCannotBeBypassedByOtpAndCanBeLifted() throws Exception {
        String email=UUID.randomUUID()+"@university.example";long userId=body(postJson("/api/admin/technicians",registration(email),a).andExpect(status().isCreated())).get("id").asLong();
        profileUpdate(userId,false,true,profileStamp(userId)).andExpect(status().isOk());
        postJson("/api/auth/verify-email",Map.of("email",email,"otp",otp(email)),a).andExpect(status().isBadRequest());
        postJson("/api/auth/resend-otp",Map.of("email",email),a).andExpect(status().isAccepted());assertEquals(1,mail.size());
        profileUpdate(userId,true,false,profileStamp(userId)).andExpect(status().isConflict());
        profileUpdate(userId,false,false,profileStamp(userId)).andExpect(status().isOk());
        postJson("/api/auth/verify-email",Map.of("email",email,"otp",otp(email)),a).andExpect(status().isOk());
    }
    @Test void profilePaginationFilteringAndTokenRevocationWork() throws Exception {
        read("/api/admin/technicians?search="+tech.getEmail()+"&size=1&sort=email,asc",a).andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(1));
        read("/api/admin/technicians?size=101",a).andExpect(status().isBadRequest());read("/api/admin/technicians?sort=passwordHash,asc",a).andExpect(status().isBadRequest());
        String before=profileStamp(tech.getId());profileUpdate(tech.getId(),false,true,before).andExpect(status().isOk());
        read("/api/technician/workload",t).andExpect(status().isUnauthorized());
        profileUpdate(tech.getId(),true,false,before).andExpect(status().isConflict());profileUpdate(tech.getId(),true,false,profileStamp(tech.getId())).andExpect(status().isOk());
        read("/api/technician/workload",t).andExpect(status().isUnauthorized());
        read("/api/technician/workload",tokens.issue(users.findById(tech.getId()).orElseThrow()).accessToken()).andExpect(status().isOk());
        read("/api/admin/technicians/"+reporter.getId(),a).andExpect(status().isNotFound());
    }
    @Test void assignmentRecordsActorTimestampAndHistoryAndInvalidatesStaleToken() throws Exception {
        verifyRequest();String before=stamp();var data=new HashMap<String,Object>(Map.of("technicianId",tech.getId(),"expectedUpdatedAt",before));data.put("assignedBy",reporter.getId());data.put("assignedAt","2000-01-01T00:00:00Z");
        postJson(adminPath()+"/assignments",data,a).andExpect(status().isCreated()).andExpect(jsonPath("status").value("ASSIGNED"));
        assertEquals(admin.getId(),jdbc.queryForObject("select assigned_by from assignments where id=?",Long.class,activeAssignment()));assertNotEquals(before,stamp());assertEquals(3,historyCount());
        read(publicPath()+"/assignment",t).andExpect(status().isOk()).andExpect(jsonPath("technicianId").value(tech.getId()));
        assign(otherTech.getId(),false,before).andExpect(status().isConflict());
    }
    @Test void assignmentRejectsOpenTerminalInactiveUnverifiedAndNonTechnicianTargets() throws Exception {
        assign(tech.getId(),false,stamp()).andExpect(status().isConflict());verifyRequest();
        assign(reporter.getId(),false,stamp()).andExpect(status().isConflict());assign(Long.MAX_VALUE,false,stamp()).andExpect(status().isNotFound());
        tech.setEnabled(false);users.saveAndFlush(tech);assign(tech.getId(),false,stamp()).andExpect(status().isConflict());
        tech.setEnabled(true);tech.setEmailVerified(false);users.saveAndFlush(tech);assign(tech.getId(),false,stamp()).andExpect(status().isConflict());
        for(String state:List.of("REJECTED","CLOSED","RESOLVED")) {jdbc.update("update maintenance_requests set status=? where id=?",state,id);assign(otherTech.getId(),false,stamp()).andExpect(status().isConflict());}
    }
    @Test void reassignmentPreservesEpisodesAndRemovesOldTechnicianAccess() throws Exception {
        assigned();long old=activeAssignment();long count=historyCount();
        assign(otherTech.getId(),true,stamp()).andExpect(status().isOk()).andExpect(jsonPath("status").value("ASSIGNED"));
        assertEquals(count,historyCount());assertEquals("REASSIGNED",jdbc.queryForObject("select end_reason from assignments where id=?",String.class,old));
        assertEquals("Replacement required",jdbc.queryForObject("select reassignment_reason from assignments where id=?",String.class,old));assertNotEquals(old,activeAssignment());
        read(publicPath(),t).andExpect(status().isNotFound());patchJson(techPath()+"/accept",action(null),t).andExpect(status().isNotFound());
        read(publicPath()+"/assignment",otherT).andExpect(status().isOk());
        read(adminPath()+"/assignments",a).andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(2));
    }
    @Test void reassigningStartedOrHeldWorkRequiresReasonAndResetsAcceptance() throws Exception {
        started();patchJson(techPath()+"/hold",action("Waiting for replacement part"),t).andExpect(status().isOk());
        patchJson(adminPath()+"/assignment",Map.of("technicianId",otherTech.getId(),"expectedUpdatedAt",stamp()),a).andExpect(status().isBadRequest());
        assign(tech.getId(),true,stamp()).andExpect(status().isConflict());assign(otherTech.getId(),true,stamp()).andExpect(status().isOk());
        read(publicPath()+"/assignment",otherT).andExpect(jsonPath("acceptedAt").isEmpty()).andExpect(jsonPath("startedAt").isEmpty());
        patchJson(techPath()+"/start",action(null),otherT).andExpect(status().isConflict());
    }
    @Test void technicianQueueAndWorkloadAreScopedAndPaginated() throws Exception {
        assigned();
        read("/api/maintenance-requests?buildingId="+buildingId+"&status=ASSIGNED&size=1&sort=priority,asc",t).andExpect(status().isOk()).andExpect(jsonPath("totalElements").value(1));
        read("/api/maintenance-requests?buildingId="+buildingId+"&technicianId="+tech.getId(),otherT).andExpect(jsonPath("totalElements").value(0));
        read("/api/technician/workload",t).andExpect(status().isOk()).andExpect(jsonPath("totalActive").value(1));
        read("/api/technician/workload",otherT).andExpect(jsonPath("totalActive").value(0));
        read("/api/admin/technicians/"+tech.getId()+"/workload",a).andExpect(jsonPath("totalActive").value(1));
        read(publicPath()+"/assignment",otherT).andExpect(status().isNotFound());read(adminPath()+"/assignments",t).andExpect(status().isForbidden());
    }
    @Test void workRequiresAcceptanceAndSupportsHoldResumeAndAppendOnlyProgressNotes() throws Exception {
        assigned();patchJson(techPath()+"/start",action(null),t).andExpect(status().isConflict());
        String old=stamp();patchJson(techPath()+"/accept",action(null),t).andExpect(status().isOk());assertNotEquals(old,stamp());
        patchJson(techPath()+"/accept",action(null),t).andExpect(status().isConflict());patchJson(techPath()+"/start",action("Investigating issue"),t).andExpect(status().isOk());
        patchJson(techPath()+"/progress",action("Diagnosed broken component"),t).andExpect(status().isOk());
        patchJson(techPath()+"/hold",action("bad"),t).andExpect(status().isBadRequest());patchJson(techPath()+"/hold",action("Waiting for spare parts"),t).andExpect(status().isOk());
        patchJson(techPath()+"/resume",action("Parts received"),t).andExpect(status().isOk());
        String notes=body(read(publicPath()+"/assignment",t)).get("repairNotes").asText();assertTrue(notes.contains("Investigating issue"));assertTrue(notes.contains("Diagnosed broken component"));assertTrue(notes.contains("Parts received"));
    }
    @Test void resolutionConfirmationClosureCompleteTheLifecycleWithAtomicHistory() throws Exception {
        started();long episode=activeAssignment();resolve(t,List.of()).andExpect(status().isOk()).andExpect(jsonPath("status").value("RESOLVED"));
        var completed=jdbc.queryForMap("select accepted_at,started_at,resolved_at,ended_at,end_reason,repair_notes from assignments where id=?",episode);
        assertEquals("RESOLVED",completed.get("end_reason"));assertEquals(completed.get("resolved_at"),completed.get("ended_at"));assertNotNull(completed.get("started_at"));
        read(publicPath(),t).andExpect(status().isNotFound());
        patchJson(publicPath()+"/confirmation",action(null),r).andExpect(status().isOk()).andExpect(jsonPath("status").value("CONFIRMED"));
        patchJson(adminPath()+"/status",Map.of("status","CLOSED","expectedUpdatedAt",stamp()),a).andExpect(status().isOk());
        assertEquals(7,historyCount());assertEquals(1,jdbc.queryForObject("select count(*) from assignments where request_id=?",Integer.class,id));
    }
    @Test void wrongOwnersPrematureAndDuplicateActionsAreRejected() throws Exception {
        patchJson(publicPath()+"/confirmation",action(null),r).andExpect(status().isConflict());assigned();
        patchJson(techPath()+"/accept",action(null),otherT).andExpect(status().isNotFound());resolve(t,List.of()).andExpect(status().isConflict());
        patchJson(techPath()+"/accept",action(null),t).andExpect(status().isOk());patchJson(techPath()+"/start",action(null),t).andExpect(status().isOk());
        patchJson(techPath()+"/resolve",Map.of("expectedUpdatedAt",stamp(),"repairNotes","bad"),t).andExpect(status().isBadRequest());resolve(t,List.of()).andExpect(status().isOk());
        resolve(t,List.of()).andExpect(status().isConflict());patchJson(publicPath()+"/confirmation",action(null),otherR).andExpect(status().isNotFound());
        patchJson(publicPath()+"/confirmation",action(null),r).andExpect(status().isOk());patchJson(publicPath()+"/confirmation",action(null),r).andExpect(status().isConflict());
    }
    private long evidence(long requestId,long uploader,String purpose) {
        return jdbc.queryForObject("insert into attachments(request_id,uploaded_by,purpose,storage_provider,storage_key,original_filename,media_type,size_bytes) values (?,?,?,'test',?,'evidence.jpg','image/jpeg',100) returning id",Long.class,requestId,uploader,purpose,UUID.randomUUID().toString());
    }
    @Test void evidenceLinksArePersistedAndInvalidReferencesRollBack() throws Exception {
        started();long episode=activeAssignment();long own=evidence(id,tech.getId(),"REPAIR_EVIDENCE");long wrong=evidence(id,otherTech.getId(),"REPAIR_EVIDENCE");long issue=evidence(id,tech.getId(),"ISSUE_PHOTO");
        for(List<Long> ids:List.of(List.of(wrong),List.of(issue),List.of(Long.MAX_VALUE),List.of(own,own))) resolve(t,ids).andExpect(status().isBadRequest());
        assertEquals(0,jdbc.queryForObject("select count(*) from assignment_evidence where assignment_id=?",Integer.class,episode));
        resolve(t,List.of(own)).andExpect(status().isOk());assertEquals(own,jdbc.queryForObject("select attachment_id from assignment_evidence where assignment_id=?",Long.class,episode));
        read(adminPath()+"/assignments",a).andExpect(jsonPath("content[0].assignment.evidenceAttachmentIds[0]").value(own));
    }
    @Test void reopeningReverificationAndReassignmentPreserveResolutionCyclesAndRejectOldEvidence() throws Exception {
        started();long first=activeAssignment();long oldEvidence=evidence(id,tech.getId(),"REPAIR_EVIDENCE");resolve(t,List.of(oldEvidence)).andExpect(status().isOk());
        patchJson(publicPath()+"/reopening",action("Fault returned after repair"),r).andExpect(status().isOk());
        patchJson(adminPath()+"/status",Map.of("status","VERIFIED","expectedUpdatedAt",stamp()),a).andExpect(status().isOk());
        assign(tech.getId(),false,stamp()).andExpect(status().isCreated());long second=activeAssignment();assertNotEquals(first,second);
        patchJson(publicPath()+"/confirmation",action(null),r).andExpect(status().isConflict());
        patchJson(techPath()+"/accept",action(null),t).andExpect(status().isOk());patchJson(techPath()+"/start",action(null),t).andExpect(status().isOk());
        resolve(t,List.of(oldEvidence)).andExpect(status().isBadRequest());resolve(t,List.of()).andExpect(status().isOk());
        patchJson(publicPath()+"/confirmation",action(null),r).andExpect(status().isOk());
        assertEquals("RESOLVED",jdbc.queryForObject("select end_reason from assignments where id=?",String.class,first));
        assertEquals(2,jdbc.queryForObject("select count(*) from assignments where request_id=?",Integer.class,id));
    }
    @Test void staleCompletedAssignmentCannotConfirmAFabricatedLaterResolution() throws Exception {
        started();resolve(t,List.of()).andExpect(status().isOk());
        patchJson(adminPath()+"/status",Map.of("status","REOPENED","reason","Fault returned","expectedUpdatedAt",stamp()),a).andExpect(status().isOk());
        patchJson(adminPath()+"/status",Map.of("status","VERIFIED","expectedUpdatedAt",stamp()),a).andExpect(status().isOk());
        jdbc.update("update maintenance_requests set status='RESOLVED' where id=?",id);
        patchJson(publicPath()+"/confirmation",action(null),r).andExpect(status().isConflict());
    }
    @Test void deactivationRequiresReassignmentAndPreservesEndedEpisodesAndRevokesTokens() throws Exception {
        assigned();profileUpdate(tech.getId(),false,true,profileStamp(tech.getId())).andExpect(status().isConflict());long old=activeAssignment();
        assign(otherTech.getId(),true,stamp()).andExpect(status().isOk());profileUpdate(tech.getId(),false,true,profileStamp(tech.getId())).andExpect(status().isOk());
        read("/api/technician/workload",t).andExpect(status().isUnauthorized());assertEquals("REASSIGNED",jdbc.queryForObject("select end_reason from assignments where id=?",String.class,old));
    }
    @Test void roleBoundariesAndDefaultDenialCoverNewOperations() throws Exception {
        for(String token:List.of(r,t)) postJson(adminPath()+"/assignments",Map.of("technicianId",tech.getId(),"expectedUpdatedAt",stamp()),token).andExpect(status().isForbidden());
        for(String op:List.of("accept","start","hold","resume","progress","resolve"))
            for(String token:List.of(r,a)) patchJson(techPath()+"/"+op,action("Reason"),token).andExpect(status().isForbidden());
        for(String token:List.of(t,a)) patchJson(publicPath()+"/confirmation",action(null),token).andExpect(status().isForbidden());
        mvc.perform(patch(techPath()+"/accept").contentType("application/json").content(json.writeValueAsString(action(null)))).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/admin/technicians/"+tech.getId()).header("Authorization","Bearer "+a)).andExpect(status().isForbidden());
    }
    @Test void assignmentAndResolutionFailuresRollBackEveryRelatedRow() throws Exception {
        verifyRequest();String before=stamp();long count=historyCount();
        installFailure();
        try {assign(tech.getId(),false,before).andExpect(status().isConflict());assertEquals(0,jdbc.queryForObject("select count(*) from assignments where request_id=?",Integer.class,id));assertEquals(before,stamp());assertEquals(count,historyCount());}
        finally {removeFailure();}
        assign(tech.getId(),false,stamp()).andExpect(status().isCreated());patchJson(techPath()+"/accept",action(null),t).andExpect(status().isOk());patchJson(techPath()+"/start",action(null),t).andExpect(status().isOk());
        long episode=activeAssignment();long ref=evidence(id,tech.getId(),"REPAIR_EVIDENCE");before=stamp();count=historyCount();installFailure();
        try {resolve(t,List.of(ref)).andExpect(status().isConflict());assertEquals(episode,activeAssignment());assertEquals(before,stamp());assertEquals(count,historyCount());assertEquals(0,jdbc.queryForObject("select count(*) from assignment_evidence where assignment_id=?",Integer.class,episode));}
        finally {removeFailure();}
    }
    private void installFailure() {
        jdbc.execute("create function ff08_fail_history() returns trigger language plpgsql as $$ begin if NEW.request_id="+id+" and NEW.previous_status is not null then raise exception 'synthetic failure' using errcode='23514'; end if; return NEW; end $$");
        jdbc.execute("create trigger ff08_fail_history before insert on status_history for each row execute function ff08_fail_history()");
    }
    private void removeFailure() {jdbc.execute("drop trigger ff08_fail_history on status_history");jdbc.execute("drop function ff08_fail_history()");}
    private List<Integer> race(Callable<Integer> first,Callable<Integer> second) throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);var x=pool.submit(()->{start.await();return first.call();});var y=pool.submit(()->{start.await();return second.call();});start.countDown();
            return List.of(x.get(15,TimeUnit.SECONDS),y.get(15,TimeUnit.SECONDS));
        }
    }
    private void oneWinner(List<Integer> values,int success) {var sorted=new ArrayList<>(values);sorted.sort(Integer::compareTo);assertEquals(List.of(success,409),sorted);}
    @Test void concurrentAssignmentAndReassignmentKeepOneActiveEpisode() throws Exception {
        verifyRequest();String old=stamp();oneWinner(race(()->assign(tech.getId(),false,old).andReturn().getResponse().getStatus(),()->assign(otherTech.getId(),false,old).andReturn().getResponse().getStatus()),201);
        long selected=jdbc.queryForObject("select technician_id from assignments where id=?",Long.class,activeAssignment());
        long replacement=selected==tech.getId()?otherTech.getId():tech.getId();String next=stamp();
        oneWinner(race(()->assign(replacement,true,next).andReturn().getResponse().getStatus(),()->assign(replacement,true,next).andReturn().getResponse().getStatus()),200);
        assertEquals(1,jdbc.queryForObject("select count(*) from assignments where request_id=? and ended_at is null",Integer.class,id));
    }
    @Test void concurrentStartAndResolutionPreventDuplicateStatusHistory() throws Exception {
        assigned();patchJson(techPath()+"/accept",action(null),t).andExpect(status().isOk());var input=action(null);
        oneWinner(race(()->patchJson(techPath()+"/start",input,t).andReturn().getResponse().getStatus(),()->patchJson(techPath()+"/start",input,t).andReturn().getResponse().getStatus()),200);
        var resolution=Map.of("expectedUpdatedAt",stamp(),"repairNotes","Completed and tested repair");
        var outcomes=race(()->patchJson(techPath()+"/resolve",resolution,t).andReturn().getResponse().getStatus(),()->patchJson(techPath()+"/resolve",resolution,t).andReturn().getResponse().getStatus());
        assertEquals(1,outcomes.stream().filter(code->code==200).count());assertTrue(outcomes.contains(404)||outcomes.contains(409));
        assertEquals(1,jdbc.queryForObject("select count(*) from status_history where request_id=? and new_status='RESOLVED'",Integer.class,id));
    }
    @Test void resolutionVersusReassignmentHasExactlyOneWinner() throws Exception {
        started();String old=stamp();var resolution=Map.of("expectedUpdatedAt",old,"repairNotes","Completed repair successfully");
        var result=race(()->patchJson(techPath()+"/resolve",resolution,t).andReturn().getResponse().getStatus(),()->assign(otherTech.getId(),true,old).andReturn().getResponse().getStatus());
        assertEquals(1,result.stream().filter(code->code==200).count());assertTrue(result.contains(409)||result.contains(404));
        assertEquals(1,jdbc.queryForObject("select count(*) from assignments where request_id=? and ended_at is not null",Integer.class,id));
    }
    @Test void concurrentConfirmationRecordsOneHistoryEntry() throws Exception {
        started();resolve(t,List.of()).andExpect(status().isOk());var input=action(null);
        oneWinner(race(()->patchJson(publicPath()+"/confirmation",input,r).andReturn().getResponse().getStatus(),()->patchJson(publicPath()+"/confirmation",input,r).andReturn().getResponse().getStatus()),200);
        assertEquals(1,jdbc.queryForObject("select count(*) from status_history where request_id=? and new_status='CONFIRMED'",Integer.class,id));
    }
    @Test void technicianDeactivationVersusAssignmentCannotStrandWork() throws Exception {
        verifyRequest();String version=stamp(),profileVersion=profileStamp(tech.getId());
        var result=race(()->assign(tech.getId(),false,version).andReturn().getResponse().getStatus(),()->profileUpdate(tech.getId(),false,true,profileVersion).andReturn().getResponse().getStatus());
        assertTrue(result.equals(List.of(201,409))||result.equals(List.of(409,200)),result.toString());
        boolean enabled=users.findById(tech.getId()).orElseThrow().isEnabled();
        int active=jdbc.queryForObject("select count(*) from assignments where request_id=? and ended_at is null",Integer.class,id);assertEquals(enabled?1:0,active);
    }
}
