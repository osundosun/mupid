package com.osundosun.mupid.api.vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.vector.client.VectorPointStore;
import com.osundosun.mupid.api.vector.error.VectorErrorCode;

/**
 * 시험용 Qdrant — 컬렉션 하나를 메모리에 든다. Qdrant 의 의미(upsert 는 포인트를 통째로 갈아 끼우고,
 * 벡터 갱신은 준 이름만 바꾸고, 없는 포인트의 벡터 갱신은 404)를 흉내 낸다.
 */
public class InMemoryVectorPointStore implements VectorPointStore {

    public final Set<String> vectorNames = new HashSet<>(Set.of("mert", "clap", "feat_v1"));
    public final Map<UUID, Point> points = new LinkedHashMap<>();
    public boolean down;
    public int upserts;

    private void check() {
        if (down) {
            throw new BusinessException(VectorErrorCode.VECTOR_STORE_UNAVAILABLE);
        }
    }

    @Override
    public Set<String> vectorNames() {
        check();
        return Set.copyOf(vectorNames);
    }

    @Override
    public void addVector(String name, Map<String, Object> params) {
        check();
        vectorNames.add(name);
    }

    @Override
    public void upsert(List<Point> batch) {
        check();
        upserts++;
        for (Point point : batch) {
            if (!vectorNames.containsAll(point.vectors().keySet())) {
                throw new BusinessException(VectorErrorCode.VECTOR_STORE_UNAVAILABLE);
            }
            points.put(point.id(), new Point(point.id(), new LinkedHashMap<>(point.vectors()),
                new LinkedHashMap<>(point.payload())));
        }
    }

    @Override
    public void deleteTrackPointsExcept(int trackId, Collection<UUID> keep) {
        check();
        points.values().removeIf(p -> Integer.valueOf(trackId).equals(p.payload().get("track_id"))
            && !keep.contains(p.id()));
    }

    @Override
    public void deleteTracks(Collection<Integer> trackIds) {
        check();
        points.values().removeIf(p -> trackIds.contains(p.payload().get("track_id")));
    }

    @Override
    public void updateVectors(Map<UUID, Map<String, float[]>> vectors) {
        check();
        if (!points.keySet().containsAll(vectors.keySet())) {
            throw new BusinessException(VectorErrorCode.VECTOR_STORE_UNAVAILABLE);
        }
        vectors.forEach((id, named) -> points.get(id).vectors().putAll(named));
    }

    @Override
    public Set<UUID> existing(Collection<UUID> ids) {
        check();
        Set<UUID> out = new HashSet<>(ids);
        out.retainAll(points.keySet());
        return out;
    }

    @Override
    public long countWithVector(String name) {
        check();
        return points.values().stream().filter(p -> p.vectors().containsKey(name)).count();
    }

    @Override
    public Page scroll(Object offset, int limit, List<String> payloadFields) {
        check();
        List<UUID> ids = new ArrayList<>(new TreeMap<UUID, Point>(points).keySet());
        int from = offset == null ? 0 : ids.indexOf(UUID.fromString((String) offset));
        List<StoredPoint> page = new ArrayList<>();
        for (int i = from; i < Math.min(ids.size(), from + limit); i++) {
            Map<String, Object> payload = new LinkedHashMap<>();
            Point point = points.get(ids.get(i));
            payloadFields.forEach(f -> {
                if (point.payload().containsKey(f)) {
                    payload.put(f, point.payload().get(f));
                }
            });
            page.add(new StoredPoint(point.id(), payload));
        }
        int next = from + limit;
        return new Page(page, next < ids.size() ? ids.get(next).toString() : null);
    }

}
