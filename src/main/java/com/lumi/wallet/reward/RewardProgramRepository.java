package com.lumi.wallet.reward;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RewardProgramRepository extends JpaRepository<RewardProgram, String> {

    Optional<RewardProgram> findByCode(String code);

    @Query("select p from RewardProgram p where p.active = true order by p.code")
    Optional<RewardProgram> findActiveProgram();
}
