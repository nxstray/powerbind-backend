package com.powerbind.backend.cucumber.runner;

import org.junit.platform.suite.api.*;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("cucumber")
// Catatan: adapter Allure untuk Cucumber (io.qameta.allure.cucumber7jvm.AllureCucumber7Jvm,
// dari Allure 2.x) TIDAK kompatibel dengan Cucumber 8 - adapter itu memanggil
// Location.getLine() yang di cucumber-messages 34.x (wajib untuk Cucumber 8) sudah
// berubah tipe, hasilnya NoSuchMethodError dan SEMUA scenario gagal. Adapter
// pengganti untuk Cucumber 8 belum ada di Maven Central (allure-cucumber8-jvm = 404),
// jadi output Cucumber memakai formatter bawaan "pretty" (+ laporan surefire),
// sementara unit/functional/Selenium tetap masuk ke Allure lewat allure-junit5.
@ConfigurationParameter(key = "cucumber.plugin", value = "pretty")
@ConfigurationParameter(key = "cucumber.glue", value = "com.powerbind.backend.cucumber.steps")
public class CucumberTestRunner {
    // Runs all .feature files under src/test/resources/cucumber
}
