package com.example.pettrackerapplication;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Today's motion sightings, newest first. */
public class SightingHistoryActivity extends ThemedActivity {

    private RecyclerView listSightings;
    private LinearLayout emptyHistory;
    private TextView     tvHistorySubtitle;

    private final SightingAdapter adapter = new SightingAdapter();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sighting_history);

        findViewById(R.id.btnBack).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());

        listSightings     = findViewById(R.id.listSightings);
        emptyHistory      = findViewById(R.id.emptyHistory);
        tvHistorySubtitle = findViewById(R.id.tvHistorySubtitle);

        listSightings.setLayoutManager(new LinearLayoutManager(this));
        listSightings.setAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            finish();
            return;
        }
        String ownerId = user.getUid();

        new Thread(() -> {
            List<Pet> pets = AppDatabase.getInstance(getApplicationContext())
                    .petDao().getPetsForOwner(ownerId);
            Pet pet = Prefs.chooseActive(getApplicationContext(), pets);

            List<PetDataRepository.Sighting> sightings = pet == null
                    ? new ArrayList<>()
                    : new PetDataRepository(getApplicationContext())
                            .sightingsForToday(pet);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                render(sightings);
            });
        }).start();
    }

    private void render(List<PetDataRepository.Sighting> sightings) {
        boolean empty = sightings.isEmpty();
        emptyHistory.setVisibility(empty ? View.VISIBLE : View.GONE);
        listSightings.setVisibility(empty ? View.GONE : View.VISIBLE);

        int withPhoto = 0;
        for (PetDataRepository.Sighting sighting : sightings) {
            if (sighting.hasPicture()) withPhoto++;
        }
        tvHistorySubtitle.setText(empty
                ? getString(R.string.collected_since_the_app_was_open)
                : getResources().getQuantityString(
                        R.plurals.sighting_summary, sightings.size(),
                        sightings.size(), withPhoto));

        adapter.submit(sightings, PiConnectionManager.getBaseUrl(this),
                roomColours(sightings));
    }

    private Map<String, Integer> roomColours(List<PetDataRepository.Sighting> sightings) {
        int[] palette = {R.color.room_1, R.color.room_2, R.color.room_3, R.color.room_4};
        Map<String, Integer> colours = new LinkedHashMap<>();
        for (PetDataRepository.Sighting sighting : sightings) {
            if (!colours.containsKey(sighting.room)) {
                colours.put(sighting.room, ContextCompat.getColor(
                        this, palette[colours.size() % palette.length]));
            }
        }
        return colours;
    }

    private static final class SightingAdapter
            extends RecyclerView.Adapter<SightingAdapter.Row> {

        private final List<PetDataRepository.Sighting> sightings = new ArrayList<>();
        private Map<String, Integer> roomColours = new LinkedHashMap<>();
        private String baseUrl;

        void submit(List<PetDataRepository.Sighting> updated, String baseUrl,
                    Map<String, Integer> roomColours) {
            this.sightings.clear();
            this.sightings.addAll(updated);
            this.baseUrl = baseUrl;
            this.roomColours = roomColours;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Row onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Row(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_sighting, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Row row, int position) {
            row.bind(sightings.get(position), baseUrl, roomColours);
        }

        @Override
        public int getItemCount() {
            return sightings.size();
        }

        static final class Row extends RecyclerView.ViewHolder {
            private final ImageView image;
            private final TextView time, room, noPicture;
            private final View dot;

            Row(@NonNull View itemView) {
                super(itemView);
                image     = itemView.findViewById(R.id.imgSighting);
                time      = itemView.findViewById(R.id.tvSightingTime);
                room      = itemView.findViewById(R.id.tvSightingRoom);
                noPicture = itemView.findViewById(R.id.tvNoPicture);
                dot       = itemView.findViewById(R.id.roomDot);
            }

            void bind(PetDataRepository.Sighting sighting, String baseUrl,
                      Map<String, Integer> roomColours) {
                time.setText(sighting.time);
                room.setText(sighting.room);

                Integer colour = roomColours.get(sighting.room);
                dot.setBackgroundColor(colour == null ? 0x00000000 : colour);

                boolean showable = sighting.hasPicture() && baseUrl != null;
                noPicture.setVisibility(showable ? View.GONE : View.VISIBLE);
                noPicture.setText(sighting.hasPicture()
                        ? R.string.photo_needs_the_pi
                        : R.string.no_picture_for_this_one);

                if (!showable) {
                    Glide.with(image).clear(image);
                    image.setImageResource(R.drawable.ic_motion_24);
                    return;
                }

                Glide.with(image)
                        .load(baseUrl + "/snapshot?name=" + sighting.snapshotName)
                        .centerCrop()
                        .placeholder(R.drawable.ic_motion_24)
                        .into(image);
            }
        }
    }
}
