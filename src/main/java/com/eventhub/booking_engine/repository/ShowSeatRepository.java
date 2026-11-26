package com.eventhub.booking_engine.repository;

import com.eventhub.booking_engine.schema.ShowSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public interface ShowSeatRepository extends JpaRepository<ShowSeat, Long> {

    // Safely fetch a specific seat for a specific show
    Optional<ShowSeat> findByIdAndShowId(Long id, Long showId);
}