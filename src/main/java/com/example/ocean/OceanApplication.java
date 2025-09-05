package com.example.ocean;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@MapperScan("com.example.ocean.mapper")  // MyBatis 전용 Mapper만 스캔
@EnableJpaRepositories("com.example.ocean.repository") // JPA Repo만 스캔
public class OceanApplication {

	public static void main(String[] args) {
		SpringApplication.run(OceanApplication.class, args);
	}

}
