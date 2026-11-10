package com.digithink.zsretail.core.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "member_function")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class MemberFunction extends _BaseEntity {

	@Column(nullable = false, unique = true, length = 50)
	private String code;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(columnDefinition = "TEXT")
	private String description;

	@Column(name = "display_order")
	private Integer displayOrder = 0;
}
