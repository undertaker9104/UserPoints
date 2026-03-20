package com.example.demo.repository;

import com.example.demo.model.entity.UserPoints;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserPointsRepository extends JpaRepository<UserPoints, String> {

    Optional<UserPoints> findByUserId(String userId);

    @Modifying
    @Query("UPDATE UserPoints u SET u.totalPoints = u.totalPoints + :amount, u.version = u.version + 1 " +
           "WHERE u.userId = :userId AND u.version = :version")
    int updatePointsWithOptimisticLock(@Param("userId") String userId,
                                        @Param("amount") Integer amount,
                                        @Param("version") Integer version);

    @Query("SELECT u FROM UserPoints u ORDER BY u.totalPoints DESC")
    List<UserPoints> findTopUsers();

    boolean existsByUserId(String userId);
}
