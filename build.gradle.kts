import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
	id("org.springframework.boot") version "3.1.2"
	id("io.spring.dependency-management") version "1.1.2"
	kotlin("jvm") version "1.8.22"
	kotlin("plugin.spring") version "1.8.22"
	kotlin("plugin.jpa") version "1.9.0"
	kotlin("plugin.allopen") version "1.8.22"
	kotlin("kapt") version "1.9.0"
}

group = "edu.backend"
version = "0.0.1-SNAPSHOT"

java {
	sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
	mavenCentral()
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

kapt {
	correctErrorTypes = true
}

dependencies {
	implementation("io.jsonwebtoken:jjwt-api:0.11.5")
	runtimeOnly("io.jsonwebtoken:jjwt-impl:0.11.5")
	runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.11.5")
	implementation("org.mapstruct:mapstruct:1.5.5.Final")
	kapt("org.mapstruct:mapstruct-processor:1.5.5.Final")
	annotationProcessor("org.mapstruct:mapstruct:1.5.2.Final")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	// Migraciones V1..V3 en prod (spring.flyway.enabled=true en application-prod.properties).
	implementation("org.flywaydb:flyway-core")
	implementation("jakarta.validation:jakarta.validation-api:3.0.2")
	kapt("jakarta.validation:jakarta.validation-api:3.0.2")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.2.0")
	implementation("io.swagger.core.v3:swagger-annotations-jakarta:2.2.15")
	kapt("io.swagger.core.v3:swagger-annotations-jakarta:2.2.15")
	implementation("io.swagger.core.v3:swagger-models-jakarta:2.2.15")
	kapt("io.swagger.core.v3:swagger-models-jakarta:2.2.15")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	kapt("org.springframework.boot:spring-boot-configuration-processor")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("com.google.cloud.sql:postgres-socket-factory:1.21.0")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
	testRuntimeOnly("com.h2database:h2")
}

tasks.withType<KotlinCompile> {
	kotlinOptions {
		// -Xjvm-default=all: los métodos con cuerpo de una interfaz (p. ej. los default de los mappers)
		// compilan como default de Java, así MapStruct no intenta generarlos.
		freeCompilerArgs += listOf("-Xjsr305=strict", "-Xjvm-default=all")
		jvmTarget = "17"
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	// Los tests de seguridad hacen decenas de logins desde la misma IP: el limite de intentos de RateLimitFilter se sube
	// para que no los frene. RateLimitFilterTest prueba el limite con sus propios valores.
	systemProperty("rate-limit.auth-per-minute", "100000")
	systemProperty("rate-limit.ai-per-hour", "100000")
}

tasks.named<BootRun>("bootRun") {
	systemProperty("spring.profiles.active", System.getProperty("spring.profiles.active") ?: "dev")
}
