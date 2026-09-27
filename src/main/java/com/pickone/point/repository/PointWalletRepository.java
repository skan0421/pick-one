package com.pickone.point.repository;

import com.pickone.point.domain.PointWallet;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PointWalletRepository extends JpaRepository<PointWallet, Long> {
}
