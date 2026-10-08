package com.digithink.zsretail.erp.navpospages.sync;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.erp.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.dto.ErpItemDTO;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.service.ErpItemBootstrapService;

/**
 * {@link NavPosPagesImport} through the Spring proxy of ErpItemBootstrapService: each call is one transaction (the
 * import's @Transactional, and around it the recorder aspect of a head office whose catalogue comes from the ERP).
 * Called outside any transaction (the job runner opens none), so each packet commits on its own.
 */
@Component
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
public class BootstrapNavPosPagesImport implements NavPosPagesImport {

	private final ErpItemBootstrapService bootstrap;

	public BootstrapNavPosPagesImport(ErpItemBootstrapService bootstrap) {
		this.bootstrap = bootstrap;
	}

	@Override
	public void items(List<ErpItemDTO> packet) {
		bootstrap.importItems(packet);
	}

	@Override
	public void barcodes(List<ErpItemBarcodeDTO> packet) {
		bootstrap.importItemBarcodes(packet);
	}
}
