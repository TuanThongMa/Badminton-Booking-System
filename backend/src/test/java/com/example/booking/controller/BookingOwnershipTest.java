package com.example.booking.controller;

import com.example.booking.config.SecurityConfig;
import com.example.booking.model.Booking;
import com.example.booking.model.User;
import com.example.booking.repository.UserRepository;
import com.example.booking.security.PasswordUtil;
import com.example.booking.service.BookingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({BookingController.class, AuthController.class})
@Import(SecurityConfig.class)
class BookingOwnershipTest {
    private static final LocalDate DATE = LocalDate.now().plusDays(1);
    private static final LocalTime START = LocalTime.of(9, 0);
    private static final LocalTime END = LocalTime.of(11, 0);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean BookingService bookingService;
    @MockBean UserRepository users;

    @Test
    void requestDoesNotBindAnAccountId() {
        var requestType = json.constructType(BookingController.BookingRequest.class);

        assertThat(json.getDeserializationConfig().introspect(requestType).findProperties())
                .filteredOn(BeanPropertyDefinition::couldDeserialize)
                .extracting(BeanPropertyDefinition::getName)
                .doesNotContain("userId");
    }

    @Test
    void guestBookingHasNoAccount() throws Exception {
        expectBookingFor(null);

        mvc.perform(bookingRequest())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
        verifyNoInteractions(users);
    }

    @Test
    void signedInBookingUsesTheCallersAccount() throws Exception {
        String token = signIn();
        expectBookingFor(42L);

        mvc.perform(bookingRequest().header(AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    @Test
    void deletedAccountCannotBookWithAnOldSession() throws Exception {
        String token = signIn();
        when(users.findByUsername("customer")).thenReturn(Optional.empty());

        mvc.perform(bookingRequest().header(AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(bookingService);
    }

    private String signIn() throws Exception {
        User user = new User();
        user.setId(42L);
        user.setUsername("customer");
        user.setPassword(PasswordUtil.createEncoder().encode("test-password"));
        when(users.findByUsername("customer")).thenReturn(Optional.of(user));

        String response = mvc.perform(post("/api/auth/login").contentType(APPLICATION_JSON)
                        .content("""
                                {"username":"customer","password":"test-password"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("token").asText();
    }

    private void expectBookingFor(Long userId) {
        Booking booking = new Booking();
        booking.setId(1L);
        when(bookingService.createBooking(1L, DATE, START, END, "Booking Customer", "0900000000", null, userId))
                .thenReturn(booking);
    }

    private MockHttpServletRequestBuilder bookingRequest() {
        return post("/api/bookings").contentType(APPLICATION_JSON).content("""
                {"courtId":1,"date":"%s","startTime":"09:00","endTime":"11:00",
                 "userName":"Booking Customer","userPhone":"0900000000"}
                """.formatted(DATE));
    }
}
