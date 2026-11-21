package com.example.booking.controller;

import com.example.booking.config.SecurityConfig;
import com.example.booking.model.Booking;
import com.example.booking.model.BookingStatus;
import com.example.booking.model.User;
import com.example.booking.repository.BookingRepository;
import com.example.booking.repository.CourtRepository;
import com.example.booking.repository.UserRepository;
import com.example.booking.security.JwtUtil;
import com.example.booking.service.BookingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BookingController.class)
@Import({SecurityConfig.class, BookingService.class})
class BookingCancellationTest {
    @Autowired MockMvc mvc;
    @MockBean BookingRepository bookings;
    @MockBean CourtRepository courts;
    @MockBean UserRepository users;
    Booking booking;

    @BeforeEach
    void setUp() {
        User owner = new User();
        owner.setId(7L);
        owner.setUsername("owner");
        booking = new Booking();
        booking.setId(1L);
        booking.setUser(owner);
        booking.setBookingDate(LocalDate.now().plusDays(2));
        booking.setStartTime(LocalTime.NOON);
        when(bookings.findById(1L)).thenReturn(Optional.of(booking));
        when(bookings.save(any(Booking.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private String token(String username, String role) {
        return new JwtUtil(System.getenv().getOrDefault("JWT_SECRET",
                "dev-secret-change-me-please-dev-secret-change-me"), 60000)
                .generateToken(username, Map.of("role", role));
    }

    @Test
    void anonymousIsUnauthorizedBeforeLookup() throws Exception {
        mvc.perform(delete("/api/bookings/1")).andExpect(status().isUnauthorized());
        verifyNoInteractions(bookings);
    }

    @Test
    void invalidTokenIsUnauthorized() throws Exception {
        mvc.perform(delete("/api/bookings/1").header("Authorization", "Bearer invalid"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(bookings);
    }

    @Test
    void anonymousUnknownIdIsUnauthorized() throws Exception {
        mvc.perform(delete("/api/bookings/99")).andExpect(status().isUnauthorized());
        verifyNoInteractions(bookings);
    }

    @Test
    void otherUserIsForbidden() throws Exception {
        mvc.perform(delete("/api/bookings/1").header("Authorization", "Bearer " + token("other", "CUSTOMER")))
                .andExpect(status().isForbidden());
        verify(bookings, never()).save(any());
    }

    @Test
    void ownerCanCancel() throws Exception {
        mvc.perform(delete("/api/bookings/1").header("Authorization", "Bearer " + token("owner", "CUSTOMER")))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("CANCELLED"));
    }

    @Test
    void adminCanCancelGuestBooking() throws Exception {
        booking.setUser(null);
        mvc.perform(delete("/api/bookings/1").header("Authorization", "Bearer " + token("admin", "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("status").value("CANCELLED"));
    }

    @Test
    void customerCannotCancelGuestBooking() throws Exception {
        booking.setUser(null);
        mvc.perform(delete("/api/bookings/1").header("Authorization", "Bearer " + token("owner", "CUSTOMER")))
                .andExpect(status().isForbidden());
        verify(bookings, never()).save(any());
    }

    @Test
    void authorizedUnknownIdIsNotFound() throws Exception {
        mvc.perform(delete("/api/bookings/99").header("Authorization", "Bearer " + token("owner", "CUSTOMER")))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerStillSubjectToTwoHourRule() throws Exception {
        booking.setBookingDate(LocalDate.now());
        booking.setStartTime(LocalTime.now().plusHours(1));
        mvc.perform(delete("/api/bookings/1").header("Authorization", "Bearer " + token("owner", "CUSTOMER")))
                .andExpect(status().isBadRequest());
        verify(bookings, never()).save(any());
    }

    @Test
    void alreadyCancelledBookingStillRequiresOwnership() throws Exception {
        booking.setStatus(BookingStatus.CANCELLED);
        mvc.perform(delete("/api/bookings/1").header("Authorization", "Bearer " + token("other", "CUSTOMER")))
                .andExpect(status().isForbidden());
    }
}
