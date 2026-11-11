package com.duoshield.app.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyException;
import org.signal.libsignal.protocol.ecc.Curve;
import org.signal.libsignal.protocol.ecc.ECPrivateKey;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * BIP39 seed phrase generation and cryptographic key derivation for DuoShield.
 *
 * <h3>Methods (all static, no Android dependencies)</h3>
 * <ol>
 *   <li>{@link #generateMnemonic()} — produce 12 random BIP39 English words.</li>
 *   <li>{@link #validateMnemonic(String)} — verify checksum and word membership.</li>
 *   <li>{@link #mnemonicToSeed(String)} — PBKDF2-SHA512 (BIP39 standard) → 64-byte seed.</li>
 *   <li>{@link #deriveIdentityKeyPair(byte[])} — first 32 seed bytes → Curve25519 IdentityKeyPair.</li>
 *   <li>{@link #deriveUserId(byte[])} — SHA-256(seed) first 8 bytes → Base32 → "XXXXX-XXXXX-XXX".</li>
 * </ol>
 *
 * <h3>Security invariants</h3>
 * <ul>
 *   <li>The mnemonic is NEVER stored anywhere — not in SharedPreferences, not in Firestore,
 *       not in Room, not in any log statement. Callers must enforce this.</li>
 *   <li>All derivations are deterministic: the same mnemonic always produces the same keys.</li>
 * </ul>
 */
public final class SeedPhraseHelper {

    private SeedPhraseHelper() {}

    // ──────────────────────────────────────────────────────────────────────────
    // BIP39 constants
    // ──────────────────────────────────────────────────────────────────────────

    private static final int ENTROPY_BITS    = 128;  // 16 bytes
    private static final int CHECKSUM_BITS   = 4;    // ENT/32 = 128/32 = 4
    private static final int TOTAL_BITS      = ENTROPY_BITS + CHECKSUM_BITS; // 132
    private static final int WORD_COUNT      = 12;
    private static final int BITS_PER_WORD   = 11;   // 2^11 = 2048

    // BIP39 PBKDF2 parameters (do NOT modify — these are the standard values)
    private static final String PBKDF2_ALG   = "PBKDF2WithHmacSHA512";
    private static final String MNEMONIC_SALT = "mnemonic";
    private static final int    PBKDF2_ITERS  = 2048;
    private static final int    SEED_BYTES    = 64;

    private static final AtomicReference<CachedDerivation> derivationCache = new AtomicReference<>();

    // Guarded by SeedPhraseHelper.class. A wipe blocks new key derivations until
    // an explicit new-account or validated-restore flow re-opens the gate.
    private static boolean identityDerivationBlocked = false;

    private static class CachedDerivation {
        final byte[] seedFingerprint;
        final IdentityKeyPair pair;

        CachedDerivation(byte[] seedFingerprint, IdentityKeyPair pair) {
            this.seedFingerprint = seedFingerprint.clone();
            this.pair = pair;
        }

        void clearSeedFingerprint() {
            Arrays.fill(seedFingerprint, (byte) 0);
        }
    }

    /**
     * Clears the in-process identity derivation cache populated by
     * {@link #deriveIdentityKeyPair(byte[])}.
     *
     * <p>Called from every {@code WipeHelper.eraseLocalData()} path (voluntary wipe,
     * unpair and duress). This method shares the class monitor with derivation, so cache
     * eviction cannot overlap an executing derivation. WipeHelper uses
     * {@link #blockIdentityDerivationAndClearCache()} to reject all later derivations
     * until an explicit account-creation or validated-restore entry point reopens them.
     *
     * <p>The cache-owned seed fingerprint copy is zeroed before the entry is released.
     * The {@link IdentityKeyPair} is then eligible for collection once other callers
     * release it. This is best-effort only: the pinned libsignal Java wrapper exposes
     * no public deterministic close/destroy method, and this method cannot zero native
     * key material or copies retained by the crypto provider/runtime.
     */
    public static synchronized void clearDerivationCache() {
        CachedDerivation cached = derivationCache.getAndSet(null);
        if (cached != null) {
            // The fingerprint is an app-owned byte-array copy. Clear it before
            // dropping the entry; the libsignal key object itself has no public
            // deterministic destruction API in the pinned dependency.
            cached.clearSeedFingerprint();
        }
    }

    /**
     * Blocks further identity-key derivations and evicts the cache as part of a
     * local wipe. Both actions share the class monitor with derivation, so a
     * derivation already in progress completes before the cache is cleared and
     * every later derivation is rejected.
     *
     * <p>This does not synchronously destroy libsignal's native key handles or
     * revoke references already returned to callers. That remains the separate
     * S07-L2 native-lifetime limitation.
     */
    public static synchronized void blockIdentityDerivationAndClearCache() {
        identityDerivationBlocked = true;
        clearDerivationCache();
    }

    /**
     * Re-opens identity derivation only for a deliberate new-account creation or
     * a restore after the user has supplied and validated a recovery phrase.
     * Do not call this from generic application startup or background services.
     */
    public static synchronized void allowIdentityDerivationForFreshAccountOrRestore() {
        identityDerivationBlocked = false;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Wordlist (standard BIP39 English, 2048 words, index 0–2047)
    // ──────────────────────────────────────────────────────────────────────────

    private static final String[] WORDLIST = {
        "abandon","ability","able","about","above","absent","absorb","abstract",
        "absurd","abuse","access","accident","account","accuse","achieve","acid",
        "acoustic","acquire","across","act","action","actor","actress","actual",
        "adapt","add","addict","address","adjust","admit","adult","advance",
        "advice","aerobic","affair","afford","afraid","again","age","agent",
        "agree","ahead","aim","air","airport","aisle","alarm","album",
        "alcohol","alert","alien","all","alley","allow","almost","alone",
        "alpha","already","also","alter","always","amateur","amazing","among",
        "amount","amused","analyst","anchor","ancient","anger","angle","angry",
        "animal","ankle","announce","annual","another","answer","antenna","antique",
        "anxiety","any","apart","apology","appear","apple","approve","april",
        "arch","arctic","area","arena","argue","arm","armed","armor",
        "army","around","arrange","arrest","arrive","arrow","art","artefact",
        "artist","artwork","ask","aspect","assault","asset","assist","assume",
        "asthma","athlete","atom","attack","attend","attitude","attract","auction",
        "audit","august","aunt","author","auto","autumn","average","avocado",
        "avoid","awake","aware","away","awesome","awful","awkward","axis",
        "baby","bachelor","bacon","badge","bag","balance","balcony","ball",
        "bamboo","banana","banner","bar","barely","bargain","barrel","base",
        "basic","basket","battle","beach","bean","beauty","because","become",
        "beef","before","begin","behave","behind","believe","below","belt",
        "bench","benefit","best","betray","better","between","beyond","bicycle",
        "bid","bike","bind","biology","bird","birth","bitter","black",
        "blade","blame","blanket","blast","bleak","bless","blind","blood",
        "blossom","blouse","blue","blur","blush","board","boat","body",
        "boil","bomb","bone","bonus","book","boost","border","boring",
        "borrow","boss","bottom","bounce","box","boy","bracket","brain",
        "brand","brass","brave","bread","breeze","brick","bridge","brief",
        "bright","bring","brisk","broccoli","broken","bronze","broom","brother",
        "brown","brush","bubble","buddy","budget","buffalo","build","bulb",
        "bulk","bullet","bundle","bunker","burden","burger","burst","bus",
        "business","busy","butter","buyer","buzz","cabbage","cabin","cable",
        "cactus","cage","cake","call","calm","camera","camp","can",
        "canal","cancel","candy","cannon","canoe","canvas","canyon","capable",
        "capital","captain","car","carbon","card","cargo","carpet","carry",
        "cart","case","cash","casino","castle","casual","cat","catalog",
        "catch","category","cattle","caught","cause","caution","cave","ceiling",
        "celery","cement","census","century","cereal","certain","chair","chalk",
        "champion","change","chaos","chapter","charge","chase","chat","cheap",
        "check","cheese","chef","cherry","chest","chicken","chief","child",
        "chimney","choice","choose","chronic","chuckle","chunk","churn","cigar",
        "cinnamon","circle","citizen","city","civil","claim","clap","clarify",
        "claw","clay","clean","clerk","clever","click","client","cliff",
        "climb","clinic","clip","clock","clog","close","cloth","cloud",
        "clown","club","clump","cluster","clutch","coach","coast","coconut",
        "code","coffee","coil","coin","collect","color","column","combine",
        "come","comfort","comic","common","company","concert","conduct","confirm",
        "congress","connect","consider","control","convince","cook","cool","copper",
        "copy","coral","core","corn","correct","cost","cotton","couch",
        "country","couple","course","cousin","cover","coyote","crack","cradle",
        "craft","cram","crane","crash","crater","crawl","crazy","cream",
        "credit","creek","crew","cricket","crime","crisp","critic","crop",
        "cross","crouch","crowd","crucial","cruel","cruise","crumble","crunch",
        "crush","cry","crystal","cube","culture","cup","cupboard","curious",
        "current","curtain","curve","cushion","custom","cute","cycle","dad",
        "damage","damp","dance","danger","daring","dash","daughter","dawn",
        "day","deal","debate","debris","decade","december","decide","decline",
        "decorate","decrease","deer","defense","define","defy","degree","delay",
        "deliver","demand","demise","denial","dentist","deny","depart","depend",
        "deposit","depth","deputy","derive","describe","desert","design","desk",
        "despair","destroy","detail","detect","develop","device","devote","diagram",
        "dial","diamond","diary","dice","diesel","diet","differ","digital",
        "dignity","dilemma","dinner","dinosaur","direct","dirt","disagree","discover",
        "disease","dish","dismiss","disorder","display","distance","divert","divide",
        "divorce","dizzy","doctor","document","dog","doll","dolphin","domain",
        "donate","donkey","donor","door","dose","double","dove","draft",
        "dragon","drama","drastic","draw","dream","dress","drift","drill",
        "drink","drip","drive","drop","drum","dry","duck","dumb",
        "dune","during","dust","dutch","duty","dwarf","dynamic","eager",
        "eagle","early","earn","earth","easily","east","easy","echo",
        "ecology","economy","edge","edit","educate","effort","egg","eight",
        "either","elbow","elder","electric","elegant","element","elephant","elevator",
        "elite","else","embark","embody","embrace","emerge","emotion","employ",
        "empower","empty","enable","enact","end","endless","endorse","enemy",
        "energy","enforce","engage","engine","enhance","enjoy","enlist","enough",
        "enrich","enroll","ensure","enter","entire","entry","envelope","episode",
        "equal","equip","era","erase","erode","erosion","error","erupt",
        "escape","essay","essence","estate","eternal","ethics","evidence","evil",
        "evoke","evolve","exact","example","excess","exchange","excite","exclude",
        "excuse","execute","exercise","exhaust","exhibit","exile","exist","exit",
        "exotic","expand","expect","expire","explain","expose","express","extend",
        "extra","eye","eyebrow","fabric","face","faculty","fade","faint",
        "faith","fall","false","fame","family","famous","fan","fancy",
        "fantasy","farm","fashion","fat","fatal","father","fatigue","fault",
        "favorite","feature","february","federal","fee","feed","feel","female",
        "fence","festival","fetch","fever","few","fiber","fiction","field",
        "figure","file","film","filter","final","find","fine","finger",
        "finish","fire","firm","first","fiscal","fish","fit","fitness",
        "fix","flag","flame","flash","flat","flavor","flee","flight",
        "flip","float","flock","floor","flower","fluid","flush","fly",
        "foam","focus","fog","foil","fold","follow","food","foot",
        "force","forest","forget","fork","fortune","forum","forward","fossil",
        "foster","found","fox","fragile","frame","frequent","fresh","friend",
        "fringe","frog","front","frost","frown","frozen","fruit","fuel",
        "fun","funny","furnace","fury","future","gadget","gain","galaxy",
        "gallery","game","gap","garage","garbage","garden","garlic","garment",
        "gas","gasp","gate","gather","gauge","gaze","general","genius",
        "genre","gentle","genuine","gesture","ghost","giant","gift","giggle",
        "ginger","giraffe","girl","give","glad","glance","glare","glass",
        "glide","glimpse","globe","gloom","glory","glove","glow","glue",
        "goat","goddess","gold","good","goose","gorilla","gospel","gossip",
        "govern","gown","grab","grace","grain","grant","grape","grass",
        "gravity","great","green","grid","grief","grit","grocery","group",
        "grow","grunt","guard","guess","guide","guilt","guitar","gun",
        "gym","habit","hair","half","hammer","hamster","hand","happy",
        "harbor","hard","harsh","harvest","hat","have","hawk","hazard",
        "head","health","heart","heavy","hedgehog","height","hello","helmet",
        "help","hen","hero","hidden","high","hill","hint","hip",
        "hire","history","hobby","hockey","hold","hole","holiday","hollow",
        "home","honey","hood","hope","horn","horror","horse","hospital",
        "host","hotel","hour","hover","hub","huge","human","humble",
        "humor","hundred","hungry","hunt","hurdle","hurry","hurt","husband",
        "hybrid","ice","icon","idea","identify","idle","ignore","ill",
        "illegal","illness","image","imitate","immense","immune","impact","impose",
        "improve","impulse","inch","include","income","increase","index","indicate",
        "indoor","industry","infant","inflict","inform","inhale","inherit","initial",
        "inject","injury","inmate","inner","innocent","input","inquiry","insane",
        "insect","inside","inspire","install","intact","interest","into","invest",
        "invite","involve","iron","island","isolate","issue","item","ivory",
        "jacket","jaguar","jar","jazz","jealous","jeans","jelly","jewel",
        "job","join","joke","journey","joy","judge","juice","jump",
        "jungle","junior","junk","just","kangaroo","keen","keep","ketchup",
        "key","kick","kid","kidney","kind","kingdom","kiss","kit",
        "kitchen","kite","kitten","kiwi","knee","knife","knock","know",
        "lab","label","labor","ladder","lady","lake","lamp","language",
        "laptop","large","later","latin","laugh","laundry","lava","law",
        "lawn","lawsuit","layer","lazy","leader","leaf","learn","leave",
        "lecture","left","leg","legal","legend","leisure","lemon","lend",
        "length","lens","leopard","lesson","letter","level","liar","liberty",
        "library","license","life","lift","light","like","limb","limit",
        "link","lion","liquid","list","little","live","lizard","load",
        "loan","lobster","local","lock","logic","lonely","long","loop",
        "lottery","loud","lounge","love","loyal","lucky","luggage","lumber",
        "lunar","lunch","luxury","lyrics","machine","mad","magic","magnet",
        "maid","mail","main","major","make","mammal","man","manage",
        "mandate","mango","mansion","manual","maple","marble","march","margin",
        "marine","market","marriage","mask","mass","master","match","material",
        "math","matrix","matter","maximum","maze","meadow","mean","measure",
        "meat","mechanic","medal","media","melody","melt","member","memory",
        "mention","menu","mercy","merge","merit","merry","mesh","message",
        "metal","method","middle","midnight","milk","million","mimic","mind",
        "minimum","minor","minute","miracle","mirror","misery","miss","mistake",
        "mix","mixed","mixture","mobile","model","modify","mom","moment",
        "monitor","monkey","monster","month","moon","moral","more","morning",
        "mosquito","mother","motion","motor","mountain","mouse","move","movie",
        "much","muffin","mule","multiply","muscle","museum","mushroom","music",
        "must","mutual","myself","mystery","myth","naive","name","napkin",
        "narrow","nasty","nation","nature","near","neck","need","negative",
        "neglect","neither","nephew","nerve","nest","net","network","neutral",
        "never","news","next","nice","night","noble","noise","nominee",
        "noodle","normal","north","nose","notable","note","nothing","notice",
        "novel","now","nuclear","number","nurse","nut","oak","obey",
        "object","oblige","obscure","observe","obtain","obvious","occur","ocean",
        "october","odor","off","offer","office","often","oil","okay",
        "old","olive","olympic","omit","once","one","onion","online",
        "only","open","opera","opinion","oppose","option","orange","orbit",
        "orchard","order","ordinary","organ","orient","original","orphan","ostrich",
        "other","outdoor","outer","output","outside","oval","oven","over",
        "own","owner","oxygen","oyster","ozone","pact","paddle","page",
        "pair","palace","palm","panda","panel","panic","panther","paper",
        "parade","parent","park","parrot","party","pass","patch","path",
        "patient","patrol","pattern","pause","pave","payment","peace","peanut",
        "pear","peasant","pelican","pen","penalty","pencil","people","pepper",
        "perfect","permit","person","pet","phone","photo","phrase","physical",
        "piano","picnic","picture","piece","pig","pigeon","pill","pilot",
        "pink","pioneer","pipe","pistol","pitch","pizza","place","planet",
        "plastic","plate","play","please","pledge","pluck","plug","plunge",
        "poem","poet","point","polar","pole","police","pond","pony",
        "pool","popular","portion","position","possible","post","potato","pottery",
        "poverty","powder","power","practice","praise","predict","prefer","prepare",
        "present","pretty","prevent","price","pride","primary","print","priority",
        "prison","private","prize","problem","process","produce","profit","program",
        "project","promote","proof","property","prosper","protect","proud","provide",
        "public","pudding","pull","pulp","pulse","pumpkin","punch","pupil",
        "puppy","purchase","purity","purpose","purse","push","put","puzzle",
        "pyramid","quality","quantum","quarter","question","quick","quit","quiz",
        "quote","rabbit","raccoon","race","rack","radar","radio","rail",
        "rain","raise","rally","ramp","ranch","random","range","rapid",
        "rare","rate","rather","raven","raw","razor","ready","real",
        "reason","rebel","rebuild","recall","receive","recipe","record","recycle",
        "reduce","reflect","reform","refuse","region","regret","regular","reject",
        "relax","release","relief","rely","remain","remember","remind","remove",
        "render","renew","rent","reopen","repair","repeat","replace","report",
        "require","rescue","resemble","resist","resource","response","result","retire",
        "retreat","return","reunion","reveal","review","reward","rhythm","rib",
        "ribbon","rice","rich","ride","ridge","rifle","right","rigid",
        "ring","riot","ripple","risk","ritual","rival","river","road",
        "roast","robot","robust","rocket","romance","roof","rookie","room",
        "rose","rotate","rough","round","route","royal","rubber","rude",
        "rug","rule","run","runway","rural","sad","saddle","sadness",
        "safe","sail","salad","salmon","salon","salt","salute","same",
        "sample","sand","satisfy","satoshi","sauce","sausage","save","say",
        "scale","scan","scare","scatter","scene","scheme","school","science",
        "scissors","scorpion","scout","scrap","screen","script","scrub","sea",
        "search","season","seat","second","secret","section","security","seed",
        "seek","segment","select","sell","seminar","senior","sense","sentence",
        "series","service","session","settle","setup","seven","shadow","shaft",
        "shallow","share","shed","shell","sheriff","shield","shift","shine",
        "ship","shiver","shock","shoe","shoot","shop","short","shoulder",
        "shove","shrimp","shrug","shuffle","shy","sibling","sick","side",
        "siege","sight","sign","silent","silk","silly","silver","similar",
        "simple","since","sing","siren","sister","situate","six","size",
        "skate","sketch","ski","skill","skin","skirt","skull","slab",
        "slam","sleep","slender","slice","slide","slight","slim","slogan",
        "slot","slow","slush","small","smart","smile","smoke","smooth",
        "snack","snake","snap","sniff","snow","soap","soccer","social",
        "sock","soda","soft","solar","soldier","solid","solution","solve",
        "someone","song","soon","sorry","sort","soul","sound","soup",
        "source","south","space","spare","spatial","spawn","speak","special",
        "speed","spell","spend","sphere","spice","spider","spike","spin",
        "spirit","split","spoil","sponsor","spoon","sport","spot","spray",
        "spread","spring","spy","square","squeeze","squirrel","stable","stadium",
        "staff","stage","stairs","stamp","stand","start","state","stay",
        "steak","steel","stem","step","stereo","stick","still","sting",
        "stock","stomach","stone","stool","story","stove","strategy","street",
        "strike","strong","struggle","student","stuff","stumble","style","subject",
        "submit","subway","success","such","sudden","suffer","sugar","suggest",
        "suit","summer","sun","sunny","sunset","super","supply","supreme",
        "sure","surface","surge","surprise","surround","survey","suspect","sustain",
        "swallow","swamp","swap","swarm","swear","sweet","swift","swim",
        "swing","switch","sword","symbol","symptom","syrup","system","table",
        "tackle","tag","tail","talent","talk","tank","tape","target",
        "task","taste","tattoo","taxi","teach","team","tell","ten",
        "tenant","tennis","tent","term","test","text","thank","that",
        "theme","then","theory","there","they","thing","this","thought",
        "three","thrive","throw","thumb","thunder","ticket","tide","tiger",
        "tilt","timber","time","tiny","tip","tired","tissue","title",
        "toast","tobacco","today","toddler","toe","together","toilet","token",
        "tomato","tomorrow","tone","tongue","tonight","tool","tooth","top",
        "topic","topple","torch","tornado","tortoise","toss","total","tourist",
        "toward","tower","town","toy","track","trade","traffic","tragic",
        "train","transfer","trap","trash","travel","tray","treat","tree",
        "trend","trial","tribe","trick","trigger","trim","trip","trophy",
        "trouble","truck","true","truly","trumpet","trust","truth","try",
        "tube","tuition","tumble","tuna","tunnel","turkey","turn","turtle",
        "twelve","twenty","twice","twin","twist","two","type","typical",
        "ugly","umbrella","unable","unaware","uncle","uncover","under","undo",
        "unfair","unfold","unhappy","uniform","unique","unit","universe","unknown",
        "unlock","until","unusual","unveil","update","upgrade","uphold","upon",
        "upper","upset","urban","urge","usage","use","used","useful",
        "useless","usual","utility","vacant","vacuum","vague","valid","valley",
        "valve","van","vanish","vapor","various","vast","vault","vehicle",
        "velvet","vendor","venture","venue","verb","verify","version","very",
        "vessel","veteran","viable","vibrant","vicious","victory","video","view",
        "village","vintage","violin","virtual","virus","visa","visit","visual",
        "vital","vivid","vocal","voice","void","volcano","volume","vote",
        "voyage","wage","wagon","wait","walk","wall","walnut","want",
        "warfare","warm","warrior","wash","wasp","waste","water","wave",
        "way","wealth","weapon","wear","weasel","weather","web","wedding",
        "weekend","weird","welcome","west","wet","whale","what","wheat",
        "wheel","when","where","whip","whisper","wide","width","wife",
        "wild","will","win","window","wine","wing","wink","winner",
        "winter","wire","wisdom","wise","wish","witness","wolf","woman",
        "wonder","wood","wool","word","work","world","worry","worth",
        "wrap","wreck","wrestle","wrist","write","wrong","yard","year",
        "yellow","you","young","youth","zebra","zero","zone","zoo"
    };

    // Reverse lookup: word → index, built lazily
    private static volatile Map<String, Integer> wordIndex;

    private static Map<String, Integer> getWordIndex() {
        if (wordIndex == null) {
            synchronized (SeedPhraseHelper.class) {
                if (wordIndex == null) {
                    Map<String, Integer> map = new HashMap<>(2048 * 2);
                    for (int i = 0; i < WORDLIST.length; i++) {
                        map.put(WORDLIST[i], i);
                    }
                    wordIndex = map;
                }
            }
        }
        return wordIndex;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // a) generateMnemonic
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Generates a 12-word BIP39 mnemonic from 128 bits of SecureRandom entropy.
     *
     * <p>Algorithm (BIP39 with ENT=128, CS=4, MS=12):
     * <ol>
     *   <li>Generate 16 random bytes (128 bits).</li>
     *   <li>SHA-256 of those 16 bytes; take the first 4 bits as a checksum.</li>
     *   <li>Concatenate entropy (128 bits) + checksum (4 bits) = 132 bits.</li>
     *   <li>Split 132 bits into 12 groups of 11 bits each.</li>
     *   <li>Each group is an index (0–2047) into the BIP39 English wordlist.</li>
     * </ol>
     *
     * @return 12 BIP39 words separated by single spaces. NEVER log or store this string.
     */
    public static String generateMnemonic() throws Exception {
        byte[] entropy = new byte[16]; // 128 bits
        new SecureRandom().nextBytes(entropy);

        byte[] hash = sha256(entropy);

        // Build the 132-bit combined array.
        // combined[0..15] = entropy bytes, combined[16] = high nibble of hash[0]
        byte[] combined = new byte[17];
        System.arraycopy(entropy, 0, combined, 0, 16);
        combined[16] = (byte) (hash[0] & 0xF0); // store 4 checksum bits in high nibble

        return indicesToMnemonic(bitsToIndices(combined));
    }

    // ──────────────────────────────────────────────────────────────────────────
    // b) validateMnemonic
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Validates a BIP39 mnemonic: word membership + checksum verification.
     *
     * @param mnemonic The space-separated 12-word phrase. Case-insensitive.
     * @return {@code true} if all words are valid BIP39 words and the checksum matches.
     */
    public static boolean validateMnemonic(String mnemonic) {
        if (mnemonic == null) return false;
        String[] parts = mnemonic.trim().split("\\s+");
        if (parts.length != WORD_COUNT) return false;

        Map<String, Integer> index = getWordIndex();
        int[] indices = new int[WORD_COUNT];
        for (int i = 0; i < WORD_COUNT; i++) {
            // S07-L3: Locale.ROOT explicitly — see canonicalizeMnemonic()'s javadoc
            // for why an implicit default-locale lower-case is the wrong call here.
            String word = parts[i].toLowerCase(Locale.ROOT);
            Integer idx = index.get(word);
            if (idx == null) return false;
            indices[i] = idx;
        }

        // Reconstruct the 132-bit combined buffer
        byte[] combined = indicesToBits(indices);

        // Extract 16-byte entropy (first 128 bits)
        byte[] entropy = Arrays.copyOf(combined, 16);

        // Extract stored 4-bit checksum (high nibble of combined[16])
        int storedChecksum = (combined[16] & 0xFF) >>> 4;

        // Recompute checksum
        byte[] hash;
        try { hash = sha256(entropy); } catch (Exception e) { return false; }
        int computedChecksum = (hash[0] & 0xFF) >>> 4;

        return storedChecksum == computedChecksum;
    }

    // ──────────────────────────────────────────────────────────────────────────
    // c) mnemonicToSeed
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Canonicalises a mnemonic into the exact form {@link #mnemonicToSeed(String)}
     * must hash: {@link Locale#ROOT}-lower-cased, internal runs of whitespace
     * collapsed to a single ASCII space, and leading/trailing whitespace trimmed
     * (NFKD normalisation happens separately, in the caller, per the BIP39 spec).
     *
     * <p>(S07-L3) {@code mnemonicToSeed} previously only trimmed the two ends and
     * NFKD-normalised — it did <em>not</em> lower-case or collapse internal
     * whitespace itself, so it silently trusted every caller to have already put
     * the string in canonical form. {@link #validateMnemonic(String)} DOES
     * tolerate mixed case and repeated whitespace (it lower-cases and splits on
     * {@code \s+} per word), so a mnemonic that <em>validates successfully</em>
     * could previously derive a completely different — and wrong — seed/identity
     * key than the canonical form of the same words, with no error raised
     * anywhere: e.g. "Abandon  ability able …" (capital first letter, a doubled
     * space from a clipboard paste) passes {@code validateMnemonic} but, before
     * this fix, hashed to a different PBKDF2 password than "abandon ability able
     * …", producing a different account. Centralising canonicalisation inside
     * {@code mnemonicToSeed} itself (rather than relying on call-site
     * pre-processing, which existed for exactly one of the three call sites) closes
     * that gap for every current and future caller.
     *
     * <p>Uses {@link Locale#ROOT} explicitly rather than the platform default
     * locale's case folding. {@code String.toLowerCase()} with no explicit locale
     * is locale-sensitive — most infamously, Turkish-locale devices lower-case
     * {@code 'I'} to the dotless {@code 'ı'} (U+0131), not {@code 'i'} — so the
     * exact same input bytes can fold to different output bytes purely based on
     * device language settings. BIP39 mnemonics are always plain ASCII English
     * words, so this cannot currently change which word a user meant, but it CAN
     * change whether the canonical, hashed form matches on a Turkish-locale
     * device vs. everywhere else, which is exactly the class of silent,
     * device-dependent key-derivation mismatch this method exists to prevent.
     */
    static String canonicalizeMnemonic(String mnemonic) {
        return mnemonic.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Derives a 64-byte seed from a mnemonic using the standard BIP39 PBKDF2 algorithm.
     *
     * <p>Parameters (fixed by BIP39 — do not change):
     * <ul>
     *   <li>Algorithm: PBKDF2-HMAC-SHA512</li>
     *   <li>Password: NFKD-normalised mnemonic</li>
     *   <li>Salt: literal ASCII bytes of {@code "mnemonic"} (8 bytes)</li>
     *   <li>Iterations: 2048</li>
     *   <li>Key length: 512 bits (64 bytes)</li>
     * </ul>
     *
     * @param mnemonic The 12-word phrase (space-separated). NEVER log this parameter.
     * @return 64-byte seed. Deterministic: same mnemonic always yields the same seed.
     */
    public static byte[] mnemonicToSeed(String mnemonic) throws Exception {
        // S07-L3 fix: canonicalise BEFORE NFKD normalisation. See
        // canonicalizeMnemonic() — this method must derive the identical seed
        // for every string a user would consider "the same mnemonic" on its
        // own, not by trusting the caller to have already normalised it.
        String canonical  = canonicalizeMnemonic(mnemonic);
        String normalised = Normalizer.normalize(canonical, Normalizer.Form.NFKD);
        char[]  password  = normalised.toCharArray();
        byte[]  salt      = MNEMONIC_SALT.getBytes("UTF-8");

        PBEKeySpec spec = new PBEKeySpec(
                password, salt, PBKDF2_ITERS, SEED_BYTES * 8);
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance(PBKDF2_ALG);
            return skf.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
            Arrays.fill(password, '\0');
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // d) deriveIdentityKeyPair
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Derives a Curve25519 {@link IdentityKeyPair} from the 64-byte seed using HKDF.
     *
     * <p>HKDF with a domain-separation label prevents the identity key material from
     * being structurally correlated with any other key derived from the same BIP39 seed
     * (e.g. a future signed pre-key or one-time pre-key path). Using raw
     * {@code Arrays.copyOf(seed, 32)} as the private key provides no such separation
     * and violates Signal's key-derivation conventions (BUG-CR01).
     *
     * <p>Uses libsignal's {@code Curve.decodePrivatePoint()} which applies the required
     * Curve25519 clamping to the raw bytes. Clamping is NOT done by hand.
     *
     * @param seed 64-byte output of {@link #mnemonicToSeed(String)}.
     * @return Deterministic {@link IdentityKeyPair}. Same seed → same key pair, always.
     * @throws InvalidKeyException if libsignal rejects the derived private key.
     */
    public static synchronized IdentityKeyPair deriveIdentityKeyPair(byte[] seed)
            throws InvalidKeyException {
        if (identityDerivationBlocked) {
            throw new IllegalStateException("Identity derivation is blocked after local wipe");
        }
        if (seed == null || seed.length < 32) {
            throw new IllegalArgumentException("seed must be at least 32 bytes");
        }

        byte[] fingerprint = null;
        byte[] info = null;
        byte[] privateBytes = null;
        try {
            try {
                fingerprint = sha256(seed);
            } catch (Exception e) {
                throw new RuntimeException("SHA-256 unavailable", e);
            }

            CachedDerivation cached = derivationCache.get();
            if (cached != null && Arrays.equals(cached.seedFingerprint, fingerprint)) {
                return cached.pair;
            }

            // Domain-separated HKDF expansion: info = ASCII label, no explicit salt
            // (HKDF default: salt = HashLen zeroes as per RFC 5869 §2.2).
            // Pure javax.crypto.Mac implementation — avoids runtime dependency on
            // libsignal-client's HKDF class which is compileOnly and absent from the APK DEX.
            info = "DuoShield Identity Key v1".getBytes(StandardCharsets.UTF_8);
            try {
                privateBytes = hkdfSha256(seed, info, 32);
            } catch (java.security.GeneralSecurityException e) {
                // HmacSHA256 is mandated by Android — this path is unreachable in practice.
                throw new InvalidKeyException("HKDF-SHA256 unavailable: " + e.getMessage());
            }
            // decodePrivatePoint imports the scalar into libsignal's native key handle.
            // Clear the app-owned serialized input immediately after the import completes.
            ECPrivateKey privateKey = Curve.decodePrivatePoint(privateBytes);
            ECPublicKey  publicKey  = privateKey.publicKey();
            IdentityKey  identityKey = new IdentityKey(publicKey);
            IdentityKeyPair pair = new IdentityKeyPair(identityKey, privateKey);

            // CachedDerivation retains its own fingerprint copy; the local copy is
            // zeroed in finally on both cache hits and new derivations.
            derivationCache.set(new CachedDerivation(fingerprint, pair));
            return pair;
        } finally {
            if (fingerprint != null) Arrays.fill(fingerprint, (byte) 0);
            if (info != null) Arrays.fill(info, (byte) 0);
            if (privateBytes != null) Arrays.fill(privateBytes, (byte) 0);
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // e) deriveUserId
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Derives a deterministic DuoShield User ID from the 64-byte seed.
     *
     * <p>SHA-256(seed) → first 8 bytes → custom 31-symbol alphabet → 13 chars
     * formatted as {@code XXXXX-XXXXX-XXX}.
     *
     * <p>Alphabet has no visually ambiguous glyphs:
     * {@code 23456789ABCDEFGHJKMNPQRSTUVWXYZ} (omits O, I, L, 0, 1).
     *
     * <p>Uses 8 bytes (64 bits) → ~13 base-31 digits, giving approximately
     * 1.8 × 10¹⁹ unique IDs — same collision resistance as the old hex format
     * but far more readable and user-friendly.
     *
     * @param seed 64-byte output of {@link #mnemonicToSeed(String)}.
     * @return User ID string like {@code "K3MNP-Q8RXA-7BC"}.
     */
    public static String deriveUserId(byte[] seed) throws Exception {
        final String ALPHA = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"; // 32 unambiguous chars (omits I, L, O, 0, 1)
        byte[] hash = sha256(seed);

        // Pack first 8 bytes into a positive BigInteger
        byte[] slice = new byte[9];               // leading 0 → always positive
        System.arraycopy(hash, 0, slice, 1, 8);
        BigInteger n = new BigInteger(slice);

        BigInteger base = BigInteger.valueOf(ALPHA.length());
        char[] digits = new char[13];
        for (int i = 12; i >= 0; i--) {
            BigInteger[] dr = n.divideAndRemainder(base);
            digits[i] = ALPHA.charAt(dr[1].intValue());
            n = dr[0];
        }
        String raw = new String(digits);
        return raw.substring(0, 5) + "-" + raw.substring(5, 10) + "-" + raw.substring(10);
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────────────────

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    /**
     * Converts the 132-bit combined buffer to 12 word indices (11 bits each).
     */
    private static int[] bitsToIndices(byte[] combined) {
        int[] indices = new int[WORD_COUNT];
        for (int i = 0; i < WORD_COUNT; i++) {
            int bitOffset  = i * BITS_PER_WORD;
            int byteOffset = bitOffset / 8;
            int bitShift   = bitOffset % 8;

            // Read 3 bytes (24 bits) starting at byteOffset to guarantee 11 bits
            int val = ((combined[byteOffset] & 0xFF) << 16)
                    | ((combined[byteOffset + 1] & 0xFF) << 8)
                    | (byteOffset + 2 < combined.length ? (combined[byteOffset + 2] & 0xFF) : 0);

            // Extract 11 bits starting at bitShift within the 24-bit window
            indices[i] = (val >>> (13 - bitShift)) & 0x7FF;
        }
        return indices;
    }

    /**
     * Converts 12 word indices back to a 132-bit combined buffer (for validation).
     */
    private static byte[] indicesToBits(int[] indices) {
        byte[] combined = new byte[17];
        for (int i = 0; i < WORD_COUNT; i++) {
            int bitOffset  = i * BITS_PER_WORD;
            int byteOffset = bitOffset / 8;
            int bitShift   = bitOffset % 8;

            // Place 11-bit index into the 24-bit window at the correct bit position
            int val = indices[i] << (13 - bitShift);
            combined[byteOffset]     |= (byte) ((val >>> 16) & 0xFF);
            combined[byteOffset + 1] |= (byte) ((val >>> 8) & 0xFF);
            if (byteOffset + 2 < combined.length) {
                combined[byteOffset + 2] |= (byte) (val & 0xFF);
            }
        }
        return combined;
    }

    private static String indicesToMnemonic(int[] indices) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < indices.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(WORDLIST[indices[i]]);
        }
        return sb.toString();
    }

    /**
     * RFC 5869 HKDF-SHA256.  Pure {@code javax.crypto.Mac} — no libsignal dependency.
     *
     * <p>Extract: PRK = HMAC-SHA256(salt=0×32, IKM=ikm)
     * <p>Expand : OKM = HMAC-SHA256(PRK, info‖0x01)  (single block, length ≤ 32)
     *
     * @param ikm    input key material
     * @param info   context / domain-separation label
     * @param length desired output length in bytes (must be ≤ 32)
     */
    public static byte[] hkdfSha256(byte[] ikm, byte[] info, int length)
            throws java.security.GeneralSecurityException {
        if (ikm == null || info == null) {
            throw new IllegalArgumentException("ikm and info must not be null");
        }
        if (length < 0 || length > 32) {
            throw new IllegalArgumentException("length must be between 0 and 32 bytes");
        }

        final String ALGO = "HmacSHA256";
        Mac mac = Mac.getInstance(ALGO);
        byte[] salt = new byte[32]; // RFC 5869 §2.2: default salt = zeroes of HashLen
        byte[] prk = null;
        byte[] okm = null;
        try {
            // Extract
            mac.init(new SecretKeySpec(salt, ALGO));
            prk = mac.doFinal(ikm);
            // Expand (one block only — length ≤ 32 = HashLen)
            mac.init(new SecretKeySpec(prk, ALGO));
            mac.update(info);
            mac.update((byte) 0x01);
            okm = mac.doFinal();
            return Arrays.copyOf(okm, length);
        } finally {
            // Clear app-owned intermediate arrays. Mac/SecretKeySpec providers may
            // retain implementation-internal copies that Java does not expose.
            Arrays.fill(salt, (byte) 0);
            if (prk != null) Arrays.fill(prk, (byte) 0);
            if (okm != null) Arrays.fill(okm, (byte) 0);
        }
    }
}
