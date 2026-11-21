package com.example.booking;

import com.example.booking.model.Court;
import com.example.booking.repository.BookingRepository;
import com.example.booking.repository.CourtRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BookingBatchIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired CourtRepository courts;
    @Autowired BookingRepository bookings;
    @Autowired JdbcTemplate jdbc;
    Long branchId;
    Court a;
    Court b;
    final LocalDate date = LocalDate.now().plusDays(30);

    @BeforeEach
    void setUp() {
        branchId = jdbc.queryForObject("INSERT INTO branches (name, location) VALUES ('Batch test', 'Test') RETURNING id", Long.class);
        a = court("Batch A");
        b = court("Batch B");
    }

    Court court(String name) {
        Long id = jdbc.queryForObject("INSERT INTO courts (name, code, branch_id) VALUES (?, ?, ?) RETURNING id",
                Long.class, name, UUID.randomUUID().toString().substring(0, 20), branchId);
        return courts.findById(id).orElseThrow();
    }

    @AfterEach
    void cleanUp() {
        bookings.deleteAll(bookings.findAll().stream()
                .filter(booking -> List.of(a.getId(), b.getId()).contains(booking.getCourt().getId())).toList());
        courts.deleteAll(List.of(a, b));
        jdbc.update("DELETE FROM branches WHERE id = ?", branchId);
    }

    String single(Long id) {
        return """
                {"courtId":%d,"date":"%s","startTime":"09:00","endTime":"11:00",
                 "userName":"Batch Test","userPhone":"0901234567"}
                """.formatted(id, date);
    }

    JsonNode post(String path, String body, int expectedStatus) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        var response = http.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
        return response.getBody();
    }

    @Test
    void conflictRollsBackEveryCourt() throws Exception {
        post("/api/bookings", single(b.getId()), 201);
        JsonNode error = post("/api/bookings/batch", batch(), 409);
        assertThat(error.get("message").asText()).contains(b.getName());
        assertThat(bookings.existsOverlappingBooking(a.getId(), date, LocalTime.of(9, 0), LocalTime.of(11, 0)))
                .as("court A must not remain booked after court B conflicts").isFalse();
        assertThat(bookings.findExact(b.getId(), date, LocalTime.of(9, 0))).hasSize(1);
    }

    String batch() {
        return single(a.getId()).replace("\"courtId\":" + a.getId(),
                "\"courtIds\":[" + a.getId() + "," + b.getId() + "]");
    }

    @Test
    void createsBothCourts() throws Exception {
        JsonNode response = post("/api/bookings/batch", batch(), 201);
        assertThat(response.get("status").asText()).isEqualTo("CONFIRMED");
        var ids = response.get("ids");
        assertThat(ids.size()).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            var booking = bookings.findById(ids.get(i).asLong()).orElseThrow();
            assertThat(booking.getCourt().getId()).isEqualTo(List.of(a.getId(), b.getId()).get(i));
        }
    }

    @Test
    void rejectsInvalidCourtListsWithoutBookings() throws Exception {
        for (String ids : List.of("[]", "null", "[null]", "[" + a.getId() + "," + a.getId() + "]")) {
            String body = single(a.getId()).replace("\"courtId\":" + a.getId(), "\"courtIds\":" + ids);
            post("/api/bookings/batch", body, 400);
        }
        post("/api/bookings/batch", single(a.getId()), 400);
        assertThat(bookings.findExact(a.getId(), date, LocalTime.of(9, 0))).isEmpty();
    }

    @Test
    void missingCourtRollsBackEarlierCourt() throws Exception {
        post("/api/bookings/batch", batch().replace("," + b.getId() + "]", ",-1]"), 404);
        assertThat(bookings.findExact(a.getId(), date, LocalTime.of(9, 0))).isEmpty();
    }

    @Test
    void maintenanceRollsBackEarlierCourt() throws Exception {
        b.setMaintenance(true);
        courts.saveAndFlush(b);
        JsonNode error = post("/api/bookings/batch", batch(), 409);
        assertThat(error.get("message").asText()).contains(b.getName());
        assertThat(bookings.findExact(a.getId(), date, LocalTime.of(9, 0))).isEmpty();
    }

    @Test
    void rejectsInvalidWindowAndMissingName() throws Exception {
        for (String body : List.of(batch().replace("11:00", "08:00"),
                batch().replace("Batch Test", ""))) {
            post("/api/bookings/batch", body, 400);
        }
        assertThat(bookings.findExact(a.getId(), date, LocalTime.of(9, 0))).isEmpty();
    }
}
