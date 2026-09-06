package com.digithink.zsretail.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.digithink.zsretail.model.AppReleaseNote;

public interface AppReleaseNoteRepository extends JpaRepository<AppReleaseNote, Long> {

    List<AppReleaseNote> findByVersionOrderByIdAsc(String version);
}
