package brent.slater.storage;

import org.springframework.boot.SpringApplication;

public class TestStorageApplication {

    public static void main(String[] args) {
        SpringApplication.from(StorageApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
