package com.pickone;

import org.springframework.boot.SpringApplication;

public class TestPickoneApplication {

	public static void main(String[] args) {
		SpringApplication.from(PickoneApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
