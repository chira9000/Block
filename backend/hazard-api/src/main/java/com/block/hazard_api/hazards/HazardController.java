package com.block.hazard_api.hazards;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * GET /hazards                                          -> every active hazard
 * GET /hazards?bbox=minLon,minLat,maxLon,maxLat         -> only hazards in that map area
 * GET /hazards?minLat=..&maxLat=..&minLon=..&maxLon=..  -> same thing, older style (still works)
 */
@RestController
public class HazardController {

    private final HazardService service;

    public HazardController(HazardService service) {
        this.service = service;
    }

    @GetMapping("/hazards")
    public List<Hazard> hazards(
            @RequestParam(required = false) String bbox,
            @RequestParam(required = false) Double minLat,
            @RequestParam(required = false) Double maxLat,
            @RequestParam(required = false) Double minLon,
            @RequestParam(required = false) Double maxLon) {

        boolean any = minLat != null || maxLat != null || minLon != null || maxLon != null;
        boolean all = minLat != null && maxLat != null && minLon != null && maxLon != null;

        if (bbox != null) {
            if (any) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Use either bbox or minLat/maxLat/minLon/maxLon, not both");
            }
            try {
                return service.getHazards(BoundingBox.parse(bbox));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            }
        }

        if (any && !all) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Give all four of minLat, maxLat, minLon, maxLon, or none of them");
        }
        BoundingBox box = all ? new BoundingBox(minLat, maxLat, minLon, maxLon) : null;
        return service.getHazards(box);
    }
}
