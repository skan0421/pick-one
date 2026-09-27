package com.pickone.hide.repository;

import com.pickone.hide.domain.HidePending;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HidePendingRepository extends JpaRepository<HidePending, Long> {

	/** 신규 가입자의 번호를 연락처에 올려둔 owner 들을 찾는다 (idx_hide_pending_phone_hmac) */
	List<HidePending> findAllByPhoneHmac(String phoneHmac);

}
