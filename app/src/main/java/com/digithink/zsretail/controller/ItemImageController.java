package com.digithink.zsretail.controller;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.service.ItemFamilyService;
import com.digithink.zsretail.service.ItemService;
import com.digithink.zsretail.service.ItemSubFamilyService;

import lombok.extern.log4j.Log4j2;

/**
 * Handles image upload, removal, and serving for ItemFamily, ItemSubFamily, and
 * Item.
 *
 * Upload resizes to max 400×400 px and saves as JPEG. Images are served with a
 * 24-hour browser cache header to minimise repeat requests.
 *
 * Endpoints: POST /item-image/{type}/{id} – upload (admin only, multipart
 * "file") DELETE /item-image/{type}/{id} – remove image (admin only) GET
 * /item-image/{type}/{id} – serve image (public, cached)
 *
 * {type} is one of: family | subfamily | item
 */
@RestController
@RequestMapping("item-image")
@Log4j2
public class ItemImageController {

	private static final int MAX_DIMENSION = 400;
	private static final String FORMAT = "jpg";
	private static final long CACHE_SECONDS = TimeUnit.HOURS.toSeconds(24);

	@Value("${app.upload.dir:uploads/pos-images}")
	private String uploadBaseDir;

	private final ItemFamilyService itemFamilyService;
	private final ItemSubFamilyService itemSubFamilyService;
	private final ItemService itemService;

	public ItemImageController(ItemFamilyService itemFamilyService, ItemSubFamilyService itemSubFamilyService,
			ItemService itemService) {
		this.itemFamilyService = itemFamilyService;
		this.itemSubFamilyService = itemSubFamilyService;
		this.itemService = itemService;
	}

	// ── Upload ────────────────────────────────────────────────────────────────

	@PostMapping("/{type}/{id}")
	public ResponseEntity<?> upload(@PathVariable String type, @PathVariable Long id,
			@RequestParam MultipartFile file) {
		if (file.isEmpty()) {
			return ResponseEntity.badRequest().body(Map.of("error", "No file provided"));
		}
		String contentType = file.getContentType();
		if (contentType == null || !contentType.startsWith("image/")) {
			return ResponseEntity.badRequest().body(Map.of("error", "File must be an image"));
		}
		try {
			String filename = id + "." + FORMAT;
			Path dir = resolveDir(type);
			Files.createDirectories(dir);
			Path dest = dir.resolve(filename);

			// Read, resize, write as JPEG
			BufferedImage original = ImageIO.read(file.getInputStream());
			if (original == null) {
				return ResponseEntity.badRequest().body(Map.of("error", "Cannot read image file"));
			}
			BufferedImage resized = resize(original, MAX_DIMENSION);
			ImageIO.write(resized, FORMAT, dest.toFile());

			// Persist filename in entity
			persistFilename(type, id, filename);

			log.info("ItemImageController: uploaded image for {} id={} -> {}", type, id, dest);
			return ResponseEntity.ok(Map.of("imageFilename", filename));

		} catch (Exception e) {
			log.error("ItemImageController: upload failed for {} id={}: {}", type, id, e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
		}
	}

	// ── Delete ────────────────────────────────────────────────────────────────

	@DeleteMapping("/{type}/{id}")
	public ResponseEntity<?> delete(@PathVariable String type, @PathVariable Long id) {
		try {
			String filename = id + "." + FORMAT;
			Path file = resolveDir(type).resolve(filename);
			Files.deleteIfExists(file);
			persistFilename(type, id, null);
			log.info("ItemImageController: deleted image for {} id={}", type, id);
			return ResponseEntity.ok(Map.of("message", "Image removed"));
		} catch (Exception e) {
			log.error("ItemImageController: delete failed for {} id={}: {}", type, id, e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
		}
	}

	// ── Serve ─────────────────────────────────────────────────────────────────

	@GetMapping("/{type}/{id}")
	public ResponseEntity<Resource> serve(@PathVariable String type, @PathVariable Long id) {
		try {
			Path file = resolveDir(type).resolve(id + "." + FORMAT);
			if (!Files.exists(file)) {
				return ResponseEntity.notFound().build();
			}
			Resource resource = new FileSystemResource(file);
			return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
					.cacheControl(CacheControl.maxAge(CACHE_SECONDS, TimeUnit.SECONDS).cachePublic()).body(resource);
		} catch (Exception e) {
			log.error("ItemImageController: serve failed for {} id={}: {}", type, id, e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
		}
	}

	// ── Helpers ───────────────────────────────────────────────────────────────

	private Path resolveDir(String type) {
		return Paths.get(uploadBaseDir, type);
	}

	/** Scale image to fit within maxDim×maxDim, preserving aspect ratio. */
	private BufferedImage resize(BufferedImage src, int maxDim) {
		int w = src.getWidth();
		int h = src.getHeight();
		if (w <= maxDim && h <= maxDim) {
			// Already small enough — just convert colour model to RGB (needed for JPEG)
			return toRgb(src);
		}
		double scale = Math.min((double) maxDim / w, (double) maxDim / h);
		int newW = (int) Math.round(w * scale);
		int newH = (int) Math.round(h * scale);
		BufferedImage result = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = result.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.drawImage(src, 0, 0, newW, newH, null);
		g.dispose();
		return result;
	}

	/** Convert to RGB (JPEG writer requires RGB, not ARGB). */
	private BufferedImage toRgb(BufferedImage src) {
		if (src.getType() == BufferedImage.TYPE_INT_RGB)
			return src;
		BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = rgb.createGraphics();
		g.drawImage(src, 0, 0, null);
		g.dispose();
		return rgb;
	}

	/** Save the imageFilename (or null on delete) back to the entity. 
	 * @throws Exception */
	private void persistFilename(String type, Long id, String filename) throws Exception {
		switch (type) {
		case "family": {
			ItemFamily f = itemFamilyService.findById(id)
					.orElseThrow(() -> new IllegalArgumentException("Family not found: " + id));
			f.setImageFilename(filename);
			itemFamilyService.save(f);
			break;
		}
		case "subfamily": {
			ItemSubFamily sf = itemSubFamilyService.findById(id)
					.orElseThrow(() -> new IllegalArgumentException("SubFamily not found: " + id));
			sf.setImageFilename(filename);
			itemSubFamilyService.save(sf);
			break;
		}
		case "item": {
			Item item = itemService.findById(id)
					.orElseThrow(() -> new IllegalArgumentException("Item not found: " + id));
			item.setImageUrl(filename);
			itemService.save(item);
			break;
		}
		default:
			throw new IllegalArgumentException("Unknown type: " + type);
		}
	}
}
