package com.badlogic.NHSS;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.BitmapFontCache;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.ScreenUtils;
import com.badlogic.gdx.utils.TimeUtils;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.audio.Music;
import com.badlogic.gdx.graphics.Pixmap;


public class Main extends ApplicationAdapter {

    // Low-res offscreen target (2x upscale to 1080x1920)
    private static final int LOW_W = 540, LOW_H = 960;
    private FrameBuffer lowResFbo;
    private TextureRegion lowResRegion;

    // Cache for static "store hours" text (so we don't recompute every frame)
    private BitmapFontCache hoursCache;

    // Portrait virtual size
    private static final float VW = 1080f;
    private static final float VH = 1920f;

    // Speeds (units per second)
    private static final float STAR_MIN_SPEED = 40f, STAR_MAX_SPEED = 120f;
    private static final float PLAYER_SPEED   = 420f;
    private static final float ALIEN_SPEED    = 80f;
    private static final float ALIEN_DROP     = 24f;
    private static final float LASER_PLAYER_V = 800f;   // up
    private static final float LASER_ALIEN_V  = -600f;  // down
    private static final long  ALIEN_SHOOT_MS = 800L;

    private OrthographicCamera cam;
    private Viewport viewport;
    private Texture white;      // 1x1 white pixel
    private SpriteBatch batch;
    private BitmapFont hudFont;   // for "Score"
    private BitmapFont bodyFont;  // for store hours
    private final GlyphLayout layout = new GlyphLayout();

    // Textures
    private Texture texPlayer, texExtra, texRed, texGreen, texYellow;

    // World
    private final Array<Star>   stars        = new Array<>();
    private final Array<Block>  blocks       = new Array<>();
    private final Array<Alien>  aliens       = new Array<>();
    private final Array<Laser>  playerLasers = new Array<>();
    private final Array<Laser>  alienLasers  = new Array<>();
    private Extra extra;

    private Player player;
    private int alienDir = 1; // 1 right, -1 left
    private long lastAlienShot = 0L;
    private long nextExtraSpawn = 0L;

    private int score = 0, lives = 3;
    private float flashAlpha = 0f;

    // --- Game state ---
    private enum GameState { PLAYING, GAME_OVER }
    private GameState state = GameState.PLAYING;

    private static final long GAME_OVER_DELAY_MS = 5_000L;
    private long gameOverAtMs = 0L;

    private static String[] STORE_HOURS;


    // Shield pattern
    private static final String[] SHAPE = {
        "  xxxxxxx",
        " xxxxxxxxx",
        "xxxxxxxxxxx",
        "xxxxxxxxxxx",
        "xxxxxxxxxxx",
        "xxx     xxx",
        "xx       xx"
    };
    private static final int BLOCK = 10;

    // Audio
    private Music music;
    private Sound sLaser, sExplosion;

    @Override public void create() {
        cam = new OrthographicCamera();
        viewport = new FitViewport(VW, VH, cam);
        viewport.apply(true);
        cam.position.set(VW/2f, VH/2f, 0);

        batch  = new SpriteBatch();

        // -- Pixel font generation with 5px Outline AND Drop Shadow
        FreeTypeFontGenerator gen = new FreeTypeFontGenerator(Gdx.files.internal("Font/Pixeled.ttf"));
        FreeTypeFontGenerator.FreeTypeFontParameter p = new FreeTypeFontGenerator.FreeTypeFontParameter();

        // 1. HUD Font (Smaller, no outline)
        p.size = MathUtils.round(VH * 0.016f);
        p.minFilter = Texture.TextureFilter.Nearest;
        p.magFilter = Texture.TextureFilter.Nearest;
        hudFont = gen.generateFont(p);

        // 2. Body Font (Large with Maximum Visibility)
        p.size = MathUtils.round(VH * 0.025f);
        p.color = Color.WHITE;        // Inner letter color

        // --- THE OUTLINE (Sharp edges) ---
        p.borderWidth = 5f;
        p.borderColor = Color.BLACK;
        p.borderStraight = true;

        // --- THE SHADOW (Adds depth/separation) ---
        p.shadowOffsetX = 4;          // Shifts shadow right
        p.shadowOffsetY = 4;          // Shifts shadow down
        p.shadowColor = new Color(0, 0, 0, 0.8f); // 80% opaque black

        bodyFont = gen.generateFont(p);
        gen.dispose();

        hudFont.setUseIntegerPositions(true);
        bodyFont.setUseIntegerPositions(true);

        // Make font atlas pages NEAREST too
        for (TextureRegion tr : hudFont.getRegions())  tr.getTexture().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        for (TextureRegion tr : bodyFont.getRegions()) tr.getTexture().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);

        // Load sprites
        texPlayer = loadNearest("Graphics/Player.png");
        texExtra  = loadNearest("Graphics/Extra.png");
        texRed    = loadNearest("Graphics/Red.png");
        texGreen  = loadNearest("Graphics/Green.png");
        texYellow = loadNearest("Graphics/Yellow.png");

        // White 1x1
        Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pm.setColor(Color.WHITE);
        pm.fill();
        white = new Texture(pm);
        white.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        pm.dispose();

        // Audio
        music = Gdx.audio.newMusic(Gdx.files.internal("Audio/Music.wav"));
        music.setLooping(true);
        music.setVolume(0.5f);

        sLaser     = Gdx.audio.newSound(Gdx.files.internal("Audio/Laser.wav"));
        sExplosion = Gdx.audio.newSound(Gdx.files.internal("Audio/Explosion.wav"));

        // FBO for low-res rendering
        lowResFbo = new FrameBuffer(Pixmap.Format.RGB565, LOW_W, LOW_H, false);
        lowResRegion = new TextureRegion(lowResFbo.getColorBufferTexture());
        lowResRegion.flip(false, true);
        lowResRegion.getTexture().setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);

        // Fetch Store Hours from Scraper
        try {
            STORE_HOURS = StoreHourScraper.fetchStoreHours();
        } catch (Exception e) {
            e.printStackTrace();
            STORE_HOURS = new String[]{
                "NERD HAVEN ARCADE", "STORE HOURS:",
                "MONDAY: CLOSED", "TUESDAY: CLOSED",
                "WEDNESDAY: CLOSED", "THURSDAY: 3 PM - 10 PM",
                "FRIDAY: NOON - 10 PM", "SATURDAY: NOON - 10 PM",
                "SUNDAY: NOON - 8 PM"
            };
        }

        // Cache the store hours text for performance
        hoursCache = new BitmapFontCache(bodyFont, true);
        float lineH = 80f; // Adjusted spacing to fit notices on screen
        float totalH = STORE_HOURS.length * lineH;
        float startY = (VH + totalH) * 0.5f;

        for (int i = 0; i < STORE_HOURS.length; i++) {
            if (STORE_HOURS[i] == null) continue;
            layout.setText(bodyFont, STORE_HOURS[i]);
            float x = (VW - layout.width) * 0.5f;
            float y = startY - i * lineH;
            hoursCache.addText(STORE_HOURS[i], x, y);
        }

        // Game Initialization
        Gdx.gl.glDisable(GL20.GL_DITHER);
        initGame();
        state = GameState.PLAYING;
        gameOverAtMs = 0L;
        if (music != null && !music.isPlaying()) music.play();
    }

    private Texture loadNearest(String path) {
        Texture t = new Texture(Gdx.files.internal(path));
        t.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
        return t;
    }

    private void initGame() {
        // Stars (build once, keep across restarts)
        if (stars.size == 0) {
            for (int i = 0; i < 120; i++) {
                stars.add(new Star(
                    MathUtils.random(VW),
                    MathUtils.random(VH),
                    MathUtils.random(STAR_MIN_SPEED, STAR_MAX_SPEED)
                ));
            }
        }

        // Player at the very bottom
        player = new Player(VW/2f, 32f, 108f, 54f);

        // Shields near the bottom (above player)
        blocks.clear();
        int count = 4;
        float shieldsY = 260f; // ~lower third
        float x0 = VW/15f;
        for (int i = 0; i < count; i++) {
            float off = i * (VW / count);
            createShield(x0, shieldsY, off);
        }

        // Aliens at the TOP, marching downward
        aliens.clear();
        alienDir = 1;
        createAliensTopDown(6, 8);

        // Lasers & UFO
        playerLasers.clear();
        alienLasers.clear();
        extra = null;
        scheduleUFO();

        score = 0; lives = 3; flashAlpha = 0f; lastAlienShot = TimeUtils.millis();

        // put the game back into PLAYING and restart music
        state = GameState.PLAYING;
        gameOverAtMs = 0L;
        if (music != null && !music.isPlaying()) music.play();
    }

    private void createShield(float xs, float ys, float offX) {
        for (int r = 0; r < SHAPE.length; r++) {
            String line = SHAPE[SHAPE.length - 1 - r]; // flip vertically
            for (int c = 0; c < line.length(); c++) {
                if (line.charAt(c) == 'x') {
                    float x = xs + c * BLOCK + offX;
                    float y = ys + r * BLOCK;           // build from bottom upward
                    blocks.add(new Block(x, y, BLOCK, BLOCK,
                        new Color(241/255f, 79/255f, 80/255f, 1f)));
                }
            }
        }
    }

    // Place aliens near the top: rows go DOWN from a top anchor.
    private void createAliensTopDown(int rows, int cols) {
        int xd = (int)(60 * 1.8f), yd = (int)(48 * 1.8f);
        float top = VH - 300f; // top margin
        float left = 70f * 1.8f;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                float x = left + c * xd;
                float y = top - r * yd;
                TexKind tk; int value;
                if (r == 0)      { tk = TexKind.YELLOW; value = 300; }
                else if (r <= 2) { tk = TexKind.GREEN;  value = 200; }
                else             { tk = TexKind.RED;    value = 100; }
                aliens.add(new Alien(x, y, 80f, 80f, tk, value));
            }
        }
    }

    private void scheduleUFO() {
        nextExtraSpawn = TimeUtils.millis() + MathUtils.random(6000, 12000);
    }

    @Override public void render() {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) Gdx.app.exit();
        float dt = Gdx.graphics.getDeltaTime();
        update(dt);

        lowResFbo.begin();
        Gdx.gl.glViewport(0, 0, LOW_W, LOW_H);
        Gdx.gl.glClearColor(30/255f, 30/255f, 30/255f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        cam.update();
        batch.setProjectionMatrix(cam.combined);
        batch.begin();

        // 1. ENABLE BLENDING AND DIM GAME OBJECTS (0.3f transparency)
        batch.enableBlending();
        batch.setColor(1f, 1f, 1f, 0.3f);

        // Stars
        for (Star s : stars) batch.draw(white, s.x - 1f, s.y - 1f, 2f, 2f);

        // Shields
        for (Block b : blocks) {
            batch.setColor(b.color.r, b.color.g, b.color.b, 0.3f);
            batch.draw(white, b.x, b.y, b.w, b.h);
        }
        batch.setColor(1f, 1f, 1f, 0.3f);

        // Lasers
        for (Laser l : playerLasers) batch.draw(white, l.x - 4f, l.y, 8f, 20f);
        batch.setColor(1f, .6f, .8f, 0.3f);
        for (Laser l : alienLasers)  batch.draw(white, l.x - 4f, l.y, 8f, 20f);
        batch.setColor(1f, 1f, 1f, 0.3f);

        // Lives
        for (int i = 0; i < lives - 1; i++) batch.draw(white, VW - 40f - i*30f, VH - 28f, 24f, 12f);

        // Aliens / UFO / Player
        for (Alien a : aliens) {
            Texture t = (a.k == TexKind.RED) ? texRed : (a.k == TexKind.GREEN) ? texGreen : texYellow;
            batch.draw(t, a.x, a.y, a.w, a.h);
        }
        if (extra != null) batch.draw(texExtra, extra.x, extra.y, 64f, 32f);
        batch.draw(texPlayer, player.x, player.y, player.w, player.h);

        // --- FIXED SCORE DRAWING ---
        // Explicitly set the font color alpha to 0.3f to match other assets
        hudFont.setColor(1f, 1f, 1f, 0.3f);
        layout.setText(hudFont, "Score: " + score);
        hudFont.draw(batch, layout, 10f, VH - 18f);

        // 2. RESET TO SOLID (1.0f) FOR THE STORE HOURS
        batch.setColor(Color.WHITE);
        // Also reset the font colors to opaque so they don't stay transparent
        hudFont.setColor(Color.WHITE);
        bodyFont.setColor(Color.WHITE);

        // GAME OVER
        if (state == GameState.GAME_OVER) {
            layout.setText(bodyFont, "GAME OVER");
            bodyFont.draw(batch, layout, (VW - layout.width) * 0.5f, VH - 60f);
        }

        // Store Hours (SOLID BRIGHT)
        hoursCache.draw(batch);

        // Flash overlay
        if (flashAlpha > 0f) {
            batch.setColor(1f, 1f, 1f, flashAlpha);
            batch.draw(white, 0, 0, VW, VH);
            batch.setColor(Color.WHITE);
        }

        batch.end();
        lowResFbo.end();

        // --- Upscale / Rotation ---
        int bw = Gdx.graphics.getBackBufferWidth();
        int bh = Gdx.graphics.getBackBufferHeight();
        Gdx.gl.glViewport(0, 0, bw, bh);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);

        batch.setProjectionMatrix(new Matrix4().setToOrtho2D(0, 0, bw, bh));
        Matrix4 xf = new Matrix4().translate(bw, 0, 0).rotate(0, 0, 1, 90).scale((float) bh / VW, (float) bw / VH, 1f);

        batch.setTransformMatrix(xf);
        batch.begin();
        batch.setColor(Color.WHITE);
        batch.draw(lowResRegion, 0, 0, VW, VH);
        batch.end();
        batch.setTransformMatrix(new Matrix4());
    }


    private void triggerGameOver() {
        if (state == GameState.GAME_OVER) return;
        state = GameState.GAME_OVER;
        gameOverAtMs = TimeUtils.millis();
        playerLasers.clear();
        alienLasers.clear();
        flashAlpha = 0f;
        if (music != null && music.isPlaying()) music.stop();   // <-- stop music here
    }

    private void update(float dt) {
        long now = TimeUtils.millis();
        dt = Math.min(dt, 1f/30f * 2f);

        // GAME OVER gate — pause EVERYTHING until restart
        if (state == GameState.GAME_OVER) {
            if (now - gameOverAtMs >= GAME_OVER_DELAY_MS) {
                initGame();
            }
            return;
        }

        // Stars animate only while playing
        for (Star s : stars) {
            s.y += s.speed * dt;
            if (s.y > VH) { s.y = 0; s.x = MathUtils.random(VW); }
        }

        // Player AI (simple dodge + weave + shoot)
        playerAI(now, dt);

        // Lasers (move + record lastY)
        for (int i = playerLasers.size-1; i >= 0; i--) {
            Laser l = playerLasers.get(i);
            l.lastY = l.y;
            l.y += l.vy * dt;
            if (l.y > VH + 50) playerLasers.removeIndex(i);
        }
        for (int i = alienLasers.size-1; i >= 0; i--) {
            Laser l = alienLasers.get(i);
            l.lastY = l.y;
            l.y += l.vy * dt;
            if (l.y < -50) alienLasers.removeIndex(i);
        }

        // Aliens move horizontally
        for (Alien a : aliens) a.x += ALIEN_SPEED * alienDir * dt;
        checkAlienEdgesAndDrop();

        // Alien shooting (downward)
        if (now - lastAlienShot >= ALIEN_SHOOT_MS && aliens.size > 0) {
            Alien s = aliens.get(MathUtils.random(aliens.size - 1));
            alienLasers.add(new Laser(s.cx(), s.cy(), LASER_ALIEN_V));
            if (sLaser != null) sLaser.play(0.4f); // quieter
            lastAlienShot = now;
        }

        // UFO across the very top
        if (extra == null && now >= nextExtraSpawn) {
            boolean fromRight = MathUtils.randomBoolean();
            extra = new Extra(fromRight ? VW + 50f : -50f, VH - 200f, fromRight ? -120f : 120f);
            scheduleUFO();
        } else if (extra != null) {
            extra.x += extra.vx * dt;
            if (extra.x < -100 || extra.x > VW + 100) extra = null;
        }

        // Collisions (this may call triggerGameOver())
        handleCollisions();

        // Respawn wave
        if (aliens.size == 0) createAliensTopDown(6, 8);

        // Flash decay
        if (flashAlpha > 0f) flashAlpha = Math.max(0f, flashAlpha - 2.5f * dt);
    }

    private void checkAlienEdgesAndDrop() {
        if (aliens.size == 0) return;
        float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        for (Alien a : aliens) { minX = Math.min(minX, a.x); maxX = Math.max(maxX, a.x + a.w); }
        if (maxX >= VW) { alienDir = -1; for (Alien a : aliens) a.y -= ALIEN_DROP; }
        if (minX <= 0f) { alienDir =  1; for (Alien a : aliens) a.y -= ALIEN_DROP; }
    }

    private void handleCollisions() {
        // ----- Player lasers
        for (int i = playerLasers.size - 1; i >= 0; i--) {
            Laser l = playerLasers.get(i);

            // Swept shield hit (laser goes UP)
            Block firstHitUp = null;
            float minX = l.x - 4f, maxX = l.x + 4f;
            float segY0 = Math.min(l.lastY, l.y), segY1 = Math.max(l.lastY, l.y);
            float earliestBottom = Float.MAX_VALUE; // earliest contact for upward travel

            for (int b = 0; b < blocks.size; b++) {
                Block bk = blocks.get(b);
                boolean xOverlap = maxX > bk.x && minX < bk.x + bk.w;
                boolean yOverlap = segY1 > bk.y && segY0 < bk.y + bk.h;
                if (xOverlap && yOverlap) {
                    if (bk.y < earliestBottom) { // lowest bottom edge along path
                        earliestBottom = bk.y;
                        firstHitUp = bk;
                    }
                }
            }
            if (firstHitUp != null) {
                blocks.removeValue(firstHitUp, true);
                playerLasers.removeIndex(i);
                flashAlpha = Math.min(1f, flashAlpha + 0.3f);
                continue;
            }

            // Aliens
            Array<Alien> hits = new Array<>();
            for (Alien a : aliens)
                if (overlaps(l.x - 4, l.y, 8, 20, a.x, a.y, a.w, a.h)) hits.add(a);

            if (hits.size > 0) {
                if (sExplosion != null) sExplosion.play(0.8f);
                for (Alien a : hits) score += a.value;
                for (Alien a : hits) aliens.removeValue(a, true);
                playerLasers.removeIndex(i);
                flashAlpha = Math.min(1f, flashAlpha + 0.7f);
                continue;
            }

            // Extra
            if (extra != null && overlaps(l.x - 4, l.y, 8, 20, extra.x, extra.y, 64, 32)) {
                score += 500;
                if (sExplosion != null) sExplosion.play(0.8f);
                extra = null;
                playerLasers.removeIndex(i);
                flashAlpha = Math.min(1f, flashAlpha + 0.7f);
                continue;
            }
        }

        // ----- Alien lasers
        for (int i = alienLasers.size - 1; i >= 0; i--) {
            Laser l = alienLasers.get(i);

            // Swept shield hit (laser goes DOWN)
            Block firstHitDown = null;
            float minX = l.x - 4f, maxX = l.x + 4f;
            float segY0 = Math.min(l.lastY, l.y), segY1 = Math.max(l.lastY, l.y);
            float earliestTop = -Float.MAX_VALUE; // earliest contact for downward travel

            for (int b = 0; b < blocks.size; b++) {
                Block bk = blocks.get(b);
                boolean xOverlap = maxX > bk.x && minX < bk.x + bk.w;
                boolean yOverlap = segY1 > bk.y && segY0 < bk.y + bk.h;
                if (xOverlap && yOverlap) {
                    float top = bk.y + bk.h;
                    if (top > earliestTop) { // highest top edge along path
                        earliestTop = top;
                        firstHitDown = bk;
                    }
                }
            }
            if (firstHitDown != null) {
                blocks.removeValue(firstHitDown, true);
                alienLasers.removeIndex(i);
                flashAlpha = Math.min(1f, flashAlpha + 0.3f);
                continue;
            }

            // Player
            if (overlaps(l.x - 4, l.y, 8, 20, player.x, player.y, player.w, player.h)) {
                alienLasers.removeIndex(i);
                lives--;
                flashAlpha = Math.min(1f, flashAlpha + 0.6f);
                if (lives <= 0) { triggerGameOver(); return; }
            }
        }

        // ----- Aliens vs shields/player
        for (int a = aliens.size - 1; a >= 0; a--) {
            Alien al = aliens.get(a);
            for (int b = blocks.size - 1; b >= 0; b--) {
                if (overlaps(al.x, al.y, al.w, al.h, blocks.get(b).x, blocks.get(b).y, blocks.get(b).w, blocks.get(b).h))
                    blocks.removeIndex(b);
            }
            if (overlaps(al.x, al.y, al.w, al.h, player.x, player.y, player.w, player.h)) {
                triggerGameOver(); return;
            }
        }
    }

    private void playerAI(long now, float dt) {
        // Dodge: pause if about to move under a bullet
        boolean dodge = false;
        for (Laser b : alienLasers) {
            float futureY = player.y + 100f;
            if (b.y <= futureY && Math.abs(b.x - (player.x + player.w/2f)) < 40f) {
                dodge = true; player.moveResumeAt = now + 500L; break;
            }
        }

        // Movement
        if (!dodge && now > player.moveResumeAt) {
            player.x += PLAYER_SPEED * player.dir * dt;
            if (player.x <= 0f)             { player.x = 0f;             player.dir = 1;  }
            if (player.x + player.w >= VW)  { player.x = VW - player.w;  player.dir = -1; }
            if (now - player.lastFlipAnchor > player.flipWindowMs) {
                player.dir *= -1;
                player.lastFlipAnchor = now;
                player.flipWindowMs = MathUtils.random(1500, 3500);
            }
        }

        // Shoot lowest alien in column (player laser up)
        if (player.ready && (now - player.lastShot > player.shootIntervalMs)) {
            Alien target = findLowestInColumn(player.x + player.w/2f, 10f);
            if (target != null) {
                player.ready = false;
                player.lastShot = now;
                player.lastCooldownStart = now;
                playerLasers.add(new Laser(player.x + player.w/2f, player.y + player.h, LASER_PLAYER_V));
                if (sLaser != null) sLaser.play(0.7f);
            }
        }
        if (!player.ready && (now - player.lastCooldownStart >= player.cooldownMs)) player.ready = true;
    }

    private Alien findLowestInColumn(float xCenter, float tol) {
        Alien best = null; float bestY = -Float.MAX_VALUE;
        for (Alien a : aliens) if (Math.abs(a.cx() - xCenter) < tol && a.y > bestY) { best = a; bestY = a.y; }
        return best;
    }

    private static boolean overlaps(float ax, float ay, float aw, float ah,
                                    float bx, float by, float bw, float bh) {
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
    }

    private Sound loadSound(String path) {
        if (Gdx.files.internal(path).exists()) return Gdx.audio.newSound(Gdx.files.internal(path));
        Gdx.app.log("audio", "Missing sound: " + path);
        return null;
    }

    private Music loadMusic(String path, boolean loop, float vol) {
        if (!Gdx.files.internal(path).exists()) { Gdx.app.log("audio", "Missing music: " + path); return null; }
        Music m = Gdx.audio.newMusic(Gdx.files.internal(path));
        m.setLooping(loop);
        m.setVolume(vol);
        return m;
    }

    private void play(Sound s, float vol) { if (s != null) s.play(vol); }

    // Data
    private enum TexKind { RED, GREEN, YELLOW }

    private static class Star { float x,y,speed; Star(float x,float y,float s){this.x=x;this.y=y;this.speed=s;} }

    private static class Block { float x,y,w,h; Color color; Block(float x,float y,float w,float h,Color c){this.x=x;this.y=y;this.w=w;this.h=h;this.color=c;} }

    private static class Alien {
        float x,y,w,h; TexKind k; int value;
        Alien(float x,float y,float w,float h,TexKind k,int v){this.x=x;this.y=y;this.w=w;this.h=h;this.k=k;this.value=v;}
        float cx(){return x + w/2f;} float cy(){return y + h/2f;}
    }

    private static class Extra { float x,y,vx; Extra(float x,float y,float vx){this.x=x;this.y=y;this.vx=vx;} }

    private static class Laser {
        float x, y, vy, lastY;
        Laser(float x, float y, float vy) {
            this.x = x;
            this.y = y;
            this.vy = vy;
            this.lastY = y; // remember where it was last frame
        }
    }

    private static class Player {
        float x,y,w,h; int dir = MathUtils.randomBoolean()?1:-1;
        long moveResumeAt = TimeUtils.millis(), lastFlipAnchor = moveResumeAt;
        int  flipWindowMs = MathUtils.random(1500, 3500);
        boolean ready = true; long lastShot = 0L, lastCooldownStart = 0L;
        long shootIntervalMs = 800L, cooldownMs = 600L;
        Player(float cx,float bottom,float w,float h){this.w=w;this.h=h;this.x=cx-w/2f;this.y=bottom;}
    }

    @Override public void resize(int width, int height) { viewport.update(width, height, true); }

    @Override
    public void dispose() {
        // 1. UI & Graphics
        if (batch != null) batch.dispose();
        if (hudFont != null) hudFont.dispose();
        if (bodyFont != null) bodyFont.dispose();
        if (lowResFbo != null) lowResFbo.dispose();

        // 2. Textures
        if (texPlayer != null) texPlayer.dispose();
        if (texExtra != null) texExtra.dispose();
        if (texRed != null) texRed.dispose();
        if (texGreen != null) texGreen.dispose();
        if (texYellow != null) texYellow.dispose();
        if (white != null) white.dispose();

        // 3. Audio
        if (music != null) music.dispose();
        if (sLaser != null) sLaser.dispose();
        if (sExplosion != null) sExplosion.dispose();

        // 4. Clear collections (not strictly disposables, but good for GC)
        stars.clear();
        blocks.clear();
        aliens.clear();
    }
}
