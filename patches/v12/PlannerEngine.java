package nl.weekplanner.ah;

import java.time.LocalDate;
import java.util.*;

/**
 * Constraint- and score-based recipe planner.
 * Hard filters enforce explicit diet/banned constraints; soft scoring ranks recipes for
 * household nutrition flags, favourites, cooking time and weekly variety.
 */
public class PlannerEngine {
    public static class Ingredient {
        public final String name;
        public final String amount;
        public final boolean pantry;
        public Ingredient(String name,String amount,boolean pantry){this.name=name;this.amount=amount;this.pantry=pantry;}
    }

    public static class Meal {
        public final String day;
        public final LocalDate date;
        public final String emoji,title,category,description;
        public final int minutes;
        public final List<Ingredient> ingredients;
        public final List<String> steps;
        Meal(LocalDate date,Recipe r){
            this.date=date; this.day=dayName(date); this.emoji=r.emoji; this.title=r.title;
            this.category=r.category; this.description=r.description; this.minutes=r.minutes;
            this.ingredients=r.ingredients; this.steps=r.steps;
        }
    }

    private static class Recipe {
        final String id,title,emoji,category,description;
        final int minutes;
        final Set<String> tags;
        final List<Ingredient> ingredients;
        final List<String> steps;
        Recipe(String id,String title,String emoji,String category,int minutes,String description,String tags,
               List<Ingredient> ingredients,String...steps){
            this.id=id;this.title=title;this.emoji=emoji;this.category=category;this.minutes=minutes;this.description=description;
            this.tags=tagSet(tags);this.ingredients=ingredients;this.steps=Arrays.asList(steps);
        }
    }

    private final List<Recipe> recipes = new ArrayList<>();
    private int variantCounter = 0;

    public PlannerEngine(){ seedRecipes(); }

    // Backward-compatible entry point.
    public List<Meal> makeWeek(LocalDate monday, Set<String> banned, int maxMinutes){
        return makeWeek(monday,banned,maxMinutes,"Gezond & gevarieerd",Collections.emptySet(),"",variantCounter++);
    }

    public List<Meal> makeWeek(LocalDate monday, Set<String> banned, int maxMinutes, String diet,
                               Set<String> nutritionFlags, String favourites, int variant){
        List<Recipe> allowed=new ArrayList<>();
        for(Recipe r:recipes) if(allowed(r,banned,maxMinutes,diet)) allowed.add(r);
        if(allowed.isEmpty()) allowed.addAll(recipes);

        Random rnd=new Random(Objects.hash(monday.toString(),diet,variant));
        List<String> targets=weeklyTargets(diet);
        List<Recipe> chosen=new ArrayList<>();
        Set<String> usedIds=new HashSet<>();
        Set<String> usedProteins=new HashSet<>();

        for(String target:targets){
            Recipe best=null; double bestScore=-1e9;
            for(Recipe r:allowed){
                if(usedIds.contains(r.id)) continue;
                double s=score(r,target,diet,nutritionFlags,favourites,usedProteins);
                s += rnd.nextDouble()*2.5; // tie-breaking variation, not enough to override major constraints
                if(s>bestScore){bestScore=s;best=r;}
            }
            if(best==null){
                for(Recipe r:allowed){
                    double s=score(r,target,diet,nutritionFlags,favourites,usedProteins)+rnd.nextDouble();
                    if(s>bestScore){bestScore=s;best=r;}
                }
            }
            if(best!=null){
                chosen.add(best);usedIds.add(best.id);
                String protein=proteinTag(best.tags); if(protein!=null)usedProteins.add(protein);
            }
        }

        while(chosen.size()<7 && !allowed.isEmpty()) chosen.add(allowed.get(chosen.size()%allowed.size()));
        List<Meal> out=new ArrayList<>();
        for(int i=0;i<7;i++) out.add(new Meal(monday.plusDays(i),chosen.get(i)));
        return out;
    }

    private boolean allowed(Recipe r,Set<String>banned,int maxMinutes,String diet){
        if(maxMinutes>0 && r.minutes>maxMinutes) return false;
        for(String ban:banned){
            String b=ban.toLowerCase(Locale.ROOT).trim(); if(b.isEmpty())continue;
            if(r.title.toLowerCase(Locale.ROOT).contains(b))return false;
            for(Ingredient i:r.ingredients) if(i.name.toLowerCase(Locale.ROOT).contains(b))return false;
        }
        switch(diet){
            case "Vegetarisch": return r.tags.contains("veg") || r.tags.contains("vegan");
            case "Veganistisch": return r.tags.contains("vegan");
            case "Ketogeen": return r.tags.contains("keto");
            case "Koolhydraatarm": return r.tags.contains("lowcarb") || r.tags.contains("keto");
            case "Low-FODMAP": return r.tags.contains("lowfodmap");
            default: return true;
        }
    }

    private double score(Recipe r,String target,String diet,Set<String>flags,String favourites,Set<String>usedProteins){
        double s=0;
        if(r.category.equals(target))s+=18;
        else if(target.equals("Anders"))s+=4;
        if(matchesDiet(r,diet))s+=12;
        String fav=favourites==null?"":favourites.toLowerCase(Locale.ROOT);
        for(String f:fav.split(",")) if(!f.trim().isEmpty() && r.title.toLowerCase(Locale.ROOT).contains(f.trim()))s+=9;

        for(String f:flags){
            String x=f.toLowerCase(Locale.ROOT);
            if(x.contains("overgang")){ if(r.tags.contains("menopause"))s+=5; if(r.tags.contains("protein"))s+=2; if(r.tags.contains("calcium"))s+=2; }
            if(x.contains("huid")){ if(r.tags.contains("skin"))s+=5; if(r.tags.contains("omega3"))s+=3; if(r.tags.contains("med"))s+=2; }
            if(x.contains("hart")){ if(r.tags.contains("heart"))s+=5; if(r.tags.contains("med")||r.tags.contains("dash"))s+=2; }
            if(x.contains("glucose")){ if(r.tags.contains("glucose"))s+=5; if(r.tags.contains("wholegrain"))s+=2; }
            if(x.contains("bloeddruk")){ if(r.tags.contains("bp")||r.tags.contains("dash"))s+=5; }
            if(x.contains("bot")){ if(r.tags.contains("calcium"))s+=5; }
            if(x.contains("spier")){ if(r.tags.contains("protein"))s+=5; }
            if(x.contains("kind")){ if(r.tags.contains("child"))s+=4; if(r.tags.contains("spicy"))s-=3; }
        }
        String p=proteinTag(r.tags); if(p!=null && usedProteins.contains(p))s-=4;
        if(r.tags.contains("ultraprocessed"))s-=2;
        if(r.tags.contains("vegetables"))s+=2;
        return s;
    }

    private boolean matchesDiet(Recipe r,String diet){
        switch(diet){
            case "Mediterraan":return r.tags.contains("med");
            case "DASH":return r.tags.contains("dash")||r.tags.contains("bp");
            case "MIND":return r.tags.contains("mind");
            case "Nordic":return r.tags.contains("nordic");
            case "Portfolio":return r.tags.contains("portfolio");
            case "Vegetarisch":return r.tags.contains("veg")||r.tags.contains("vegan");
            case "Veganistisch":return r.tags.contains("vegan");
            case "Koolhydraatarm":return r.tags.contains("lowcarb")||r.tags.contains("keto");
            case "Ketogeen":return r.tags.contains("keto");
            case "Low-FODMAP":return r.tags.contains("lowfodmap");
            default:return r.tags.contains("balanced");
        }
    }

    private List<String> weeklyTargets(String diet){
        if("Ketogeen".equals(diet)||"Koolhydraatarm".equals(diet))
            return Arrays.asList("AVG","Anders","AVG","Anders","AVG","Anders","Anders");
        if("Vegetarisch".equals(diet)||"Veganistisch".equals(diet)||"Low-FODMAP".equals(diet))
            return Arrays.asList("AVG","Pasta","Anders","Rijst","AVG","Pasta","Anders");
        return Arrays.asList("AVG","Pasta","AVG","Rijst","AVG","Pasta","Anders");
    }

    private static String proteinTag(Set<String>t){
        for(String p:Arrays.asList("salmon","fish","chicken","beef","legume","tofu","egg"))if(t.contains(p))return p;
        return null;
    }
    private static Set<String>tagSet(String s){return new LinkedHashSet<>(Arrays.asList(s.split("\\s+")));}
    private static String dayName(LocalDate d){String[] n={"Maandag","Dinsdag","Woensdag","Donderdag","Vrijdag","Zaterdag","Zondag"};return n[d.getDayOfWeek().getValue()-1];}
    private static Ingredient I(String n,String a,boolean pantry){return new Ingredient(n,a,pantry);}
    private static List<Ingredient>L(Ingredient...x){return Arrays.asList(x);}

    private void seedRecipes(){
        recipes.add(new Recipe("salmon-broccoli","Zalm, krieltjes en broccoli","🐟","AVG",30,"Vette vis met veel groente en een frisse citroen-dillesaus.","balanced med dash mind nordic heart skin menopause omega3 protein vegetables fish salmon child",
                L(I("zalmfilet","150 g p.p.",false),I("krieltjes","220 g p.p.",false),I("broccoli","200 g p.p.",false),I("citroen","½ per 2 personen",false),I("dille","1 bosje",false),I("olijfolie","1 el",true),I("magere yoghurt","50 ml p.p.",false)),
                "Verwarm de oven voor op 200 °C.","Rooster krieltjes en broccoli met olijfolie.","Bak de zalm 12–15 minuten mee.","Meng yoghurt, citroen en dille tot saus."));
        recipes.add(new Recipe("chicken-beans","Kipfilet, aardappelen en sperziebonen","🍗","AVG",30,"Klassieke AVG met magere kip en veel groente.","balanced dash heart glucose protein vegetables chicken child bp",
                L(I("kipfilet","150 g p.p.",false),I("aardappelen","220 g p.p.",false),I("sperziebonen","200 g p.p.",false),I("mosterd","1 tl",true),I("paprikapoeder","1 tl",true),I("olijfolie","1 el",true)),
                "Kook de aardappelen.","Kook of stoom de sperziebonen.","Kruid en bak de kipfilet gaar.","Serveer met een klein beetje mosterdjus."));
        recipes.add(new Recipe("cod-spinach","Kabeljauw, aardappeltjes en spinazie","🐟","AVG",25,"Milde vismaaltijd met spinazie en citroen.","balanced dash nordic heart skin protein vegetables fish child bp",
                L(I("kabeljauw","160 g p.p.",false),I("aardappeltjes","220 g p.p.",false),I("spinazie","200 g p.p.",false),I("citroen","½",false),I("olijfolie","1 el",true),I("nootmuskaat","snuf",true)),
                "Kook de aardappeltjes.","Bak de kabeljauw rustig gaar.","Laat de spinazie slinken met nootmuskaat.","Werk af met citroen."));
        recipes.add(new Recipe("meatball-cauliflower","Gehaktbal, bloemkool en aardappelpuree","🥔","AVG",35,"Comfortfood in een lichtere uitvoering met veel bloemkool.","balanced protein vegetables beef child",
                L(I("mager rundergehakt","140 g p.p.",false),I("bloemkool","220 g p.p.",false),I("aardappelen","220 g p.p.",false),I("halfvolle melk","40 ml p.p.",false),I("mosterd","1 tl",true),I("nootmuskaat","snuf",true)),
                "Maak kleine gehaktballen en bak ze gaar.","Kook bloemkool en aardappelen.","Stamp aardappelen met melk en nootmuskaat.","Serveer met bloemkool en gehaktbal."));
        recipes.add(new Recipe("pasta-bolognese","Volkoren pasta bolognese met extra groente","🍝","Pasta",30,"Bekende pasta met extra courgette, wortel en tomaat.","balanced med glucose wholegrain vegetables protein beef child",
                L(I("volkoren pasta","90 g p.p.",false),I("mager rundergehakt","100 g p.p.",false),I("passata","150 ml p.p.",false),I("courgette","100 g p.p.",false),I("wortel","70 g p.p.",false),I("ui","¼ p.p.",false),I("oregano","1 tl",true),I("Parmezaanse kaas","15 g p.p.",false)),
                "Kook de pasta beetgaar.","Bak gehakt met fijngesneden groente.","Voeg passata en oregano toe en laat 10 minuten pruttelen.","Serveer met een beetje Parmezaan."));
        recipes.add(new Recipe("pasta-chicken-spinach","Volkoren pasta met kip, spinazie en tomaat","🍝","Pasta",25,"Snelle romige pasta zonder zware roomsaus.","balanced med menopause protein calcium wholegrain vegetables chicken child",
                L(I("volkoren pasta","90 g p.p.",false),I("kipfilet","130 g p.p.",false),I("spinazie","150 g p.p.",false),I("cherrytomaten","120 g p.p.",false),I("magere roomkaas","30 g p.p.",false),I("basilicum","1 tl",true),I("knoflook","1 teen",true)),
                "Kook de pasta.","Bak kipblokjes gaar.","Voeg tomaat en spinazie toe.","Roer roomkaas en pasta erdoor."));
        recipes.add(new Recipe("pasta-pesto-salmon","Volkoren pasta pesto met zalm en courgette","🍝","Pasta",25,"Pasta met zalm, courgette en een bescheiden hoeveelheid pesto.","balanced med heart skin menopause omega3 protein vegetables wholegrain fish salmon",
                L(I("volkoren pasta","85 g p.p.",false),I("zalm","130 g p.p.",false),I("courgette","150 g p.p.",false),I("groene pesto","20 g p.p.",false),I("citroen","½",false),I("rucola","40 g p.p.",false)),
                "Kook de pasta.","Bak zalm en courgette.","Meng pasta met pesto en citroen.","Schep rucola erdoor en leg zalm erop."));
        recipes.add(new Recipe("rice-teriyaki","Zilvervliesrijst met kip teriyaki en wokgroente","🍚","Rijst",25,"Snelle rijstkom met veel groente en een niet te zoete teriyakisaus.","balanced glucose wholegrain vegetables protein chicken child",
                L(I("zilvervliesrijst","75 g p.p.",false),I("kipfilet","140 g p.p.",false),I("wokgroente","220 g p.p.",false),I("teriyakisaus","25 ml p.p.",false),I("sesamzaad","1 tl p.p.",false),I("limoen","½",false)),
                "Kook de rijst.","Roerbak kip en groente.","Voeg teriyakisaus toe.","Serveer met rijst, limoen en sesam."));
        recipes.add(new Recipe("rice-salmon-edamame","Zilvervliesrijst met zalm, edamame en wokgroente","🍚","Rijst",25,"Rijstbowl met vette vis, peulvruchten en groente.","balanced med heart skin menopause omega3 protein vegetables wholegrain fish salmon legume",
                L(I("zilvervliesrijst","75 g p.p.",false),I("zalm","140 g p.p.",false),I("edamame","80 g p.p.",false),I("wokgroente","180 g p.p.",false),I("sojasaus","15 ml p.p.",true),I("limoen","½",false)),
                "Kook de rijst.","Bak zalm en wokgroente.","Warm edamame mee.","Serveer met sojasaus en limoen."));
        recipes.add(new Recipe("madras","Knorr Wereldgerecht Kip Madras met extra groente","🍛","Rijst",30,"Favoriete Kip Madras, aangevuld met extra groente en een frisse yoghurtcomponent.","balanced chicken protein vegetables child ultraprocessed",
                L(I("Knorr Wereldgerecht Kip Madras","1 pak voor 3–4 personen",false),I("kipfilet","130 g p.p.",false),I("paprika","½ stuk p.p.",false),I("sperziebonen","120 g p.p.",false),I("appel","¼ stuk p.p.",false),I("magere yoghurt","50 ml p.p.",false)),
                "Bereid de rijst en saus volgens de verpakking.","Bak de kipfilet.","Voeg paprika en sperziebonen toe.","Maak af met appel en yoghurt zoals bij het gerecht past."));
        recipes.add(new Recipe("mex-wraps","Mexicaanse wraps met kip, bonen en avocado","🌯","Anders",30,"Gevulde wraps met kip, bonen, veel groente en frisse yoghurtsaus.","balanced med glucose wholegrain vegetables protein chicken legume child",
                L(I("volkoren wraps","2 p.p.",false),I("kipfilet","120 g p.p.",false),I("kidneybonen","80 g p.p.",false),I("paprika","½ p.p.",false),I("mais","50 g p.p.",false),I("avocado","¼ p.p.",false),I("salsa","30 g p.p.",false),I("magere yoghurt","30 g p.p.",false),I("Mexicaanse kruiden","1 tl p.p.",false)),
                "Bak kip met Mexicaanse kruiden.","Voeg paprika, bonen en mais toe.","Warm de wraps.","Vul met kipmengsel, avocado, salsa en yoghurt."));
        recipes.add(new Recipe("lentil-curry","Linzen-curry met spinazie en yoghurt","🥘","Anders",30,"Vezelrijke vegetarische curry met linzen en spinazie.","balanced med portfolio veg heart glucose protein legume vegetables calcium",
                L(I("linzen uit blik","150 g p.p.",false),I("spinazie","150 g p.p.",false),I("tomatenblokjes","150 g p.p.",false),I("currypasta","20 g p.p.",true),I("Griekse yoghurt","40 g p.p.",false),I("zilvervliesrijst","60 g p.p.",false)),
                "Kook de rijst.","Verwarm linzen met tomaat en currypasta.","Laat spinazie slinken.","Serveer met yoghurt."));
        recipes.add(new Recipe("tofu-stirfry","Tofu wok met broccoli, paprika en zilvervliesrijst","🥢","Rijst",25,"Plantaardige wokschotel met tofu en veel groente.","balanced med portfolio vegan veg heart glucose protein tofu vegetables wholegrain",
                L(I("tofu","160 g p.p.",false),I("zilvervliesrijst","70 g p.p.",false),I("broccoli","140 g p.p.",false),I("paprika","100 g p.p.",false),I("sojasaus","15 ml p.p.",true),I("sesamolie","1 tl p.p.",false),I("gember","1 tl",false)),
                "Kook de rijst.","Bak tofu krokant.","Roerbak broccoli en paprika.","Voeg sojasaus, sesamolie en gember toe."));
        recipes.add(new Recipe("vegan-bolognese","Volkoren pasta met linzen-bolognese","🍝","Pasta",30,"Volkoren pasta met linzen, tomaat en veel groente.","balanced med portfolio vegan veg heart glucose legume vegetables wholegrain",
                L(I("volkoren pasta","90 g p.p.",false),I("linzen","120 g p.p.",false),I("passata","160 ml p.p.",false),I("courgette","100 g p.p.",false),I("wortel","70 g p.p.",false),I("ui","¼ p.p.",false),I("oregano","1 tl",true)),
                "Kook de pasta.","Bak ui, wortel en courgette.","Voeg linzen, passata en oregano toe.","Laat 10 minuten pruttelen en serveer."));
        recipes.add(new Recipe("vegan-chili","Chili sin carne met bonen en avocado","🌶️","Anders",30,"Stevige vegan chili met twee soorten bonen en veel groente.","balanced portfolio vegan veg heart glucose legume vegetables wholegrain child",
                L(I("kidneybonen","100 g p.p.",false),I("zwarte bonen","100 g p.p.",false),I("tomatenblokjes","160 g p.p.",false),I("paprika","100 g p.p.",false),I("mais","50 g p.p.",false),I("avocado","¼ p.p.",false),I("zilvervliesrijst","60 g p.p.",false),I("komijn","1 tl",true)),
                "Kook de rijst.","Stoof paprika met tomaat en bonen.","Breng op smaak met komijn.","Serveer met avocado."));
        recipes.add(new Recipe("med-feta-chickpea","Mediterrane kikkererwtenbowl met feta","🥗","Anders",20,"Snelle bowl met kikkererwten, tomaat, komkommer, feta en volkoren couscous.","balanced med veg heart menopause calcium legume vegetables wholegrain",
                L(I("kikkererwten","140 g p.p.",false),I("volkoren couscous","70 g p.p.",false),I("komkommer","100 g p.p.",false),I("tomaat","120 g p.p.",false),I("feta","40 g p.p.",false),I("olijfolie","1 el",true),I("citroen","½",false)),
                "Bereid de couscous.","Spoel de kikkererwten.","Snijd komkommer en tomaat.","Meng alles met feta, citroen en olijfolie."));
        recipes.add(new Recipe("portfolio-bowl","Portfolio bowl met linzen, gerst en walnoten","🥣","Anders",30,"Plantaardige bowl met peulvruchten, volkoren graan en noten.","portfolio vegan veg heart glucose legume vegetables wholegrain",
                L(I("linzen","130 g p.p.",false),I("parelgort","70 g p.p.",false),I("broccoli","160 g p.p.",false),I("walnoten","20 g p.p.",false),I("rucola","40 g p.p.",false),I("citroen","½",false),I("olijfolie","1 el",true)),
                "Kook de parelgort.","Stoom broccoli.","Meng met linzen en rucola.","Bestrooi met walnoten en citroen."));
        recipes.add(new Recipe("nordic-salmon","Nordic zalm met dille, aardappel en rode kool","🐟","AVG",35,"Noordse combinatie van zalm, aardappel, kool en dille.","nordic mind heart skin menopause omega3 fish salmon vegetables protein",
                L(I("zalm","150 g p.p.",false),I("aardappelen","200 g p.p.",false),I("rode kool","180 g p.p.",false),I("appel","¼ p.p.",false),I("dille","1 bosje",false),I("magere yoghurt","40 g p.p.",false)),
                "Kook of rooster aardappelen.","Stoof rode kool met appel.","Bak de zalm.","Serveer met yoghurt-dillesaus."));
        recipes.add(new Recipe("keto-salmon","Keto zalm met broccoli en bloemkoolpuree","🐟","AVG",30,"Koolhydraatarme maaltijd met zalm, broccoli en bloemkoolpuree.","keto lowcarb fish salmon omega3 protein vegetables skin menopause heart",
                L(I("zalm","170 g p.p.",false),I("broccoli","200 g p.p.",false),I("bloemkool","220 g p.p.",false),I("roomkaas","25 g p.p.",false),I("olijfolie","1 el",true),I("citroen","½",false)),
                "Stoom bloemkool en pureer met roomkaas.","Stoom of rooster broccoli.","Bak zalm gaar.","Serveer met citroen."));
        recipes.add(new Recipe("keto-chicken","Keto kip met courgette, paprika en avocadosalsa","🍗","Anders",25,"Koolhydraatarme kipmaaltijd met veel groente en avocado.","keto lowcarb chicken protein vegetables child",
                L(I("kipfilet","170 g p.p.",false),I("courgette","180 g p.p.",false),I("paprika","120 g p.p.",false),I("avocado","½ p.p.",false),I("limoen","½",false),I("olijfolie","1 el",true)),
                "Bak de kipfilet.","Rooster courgette en paprika.","Prak avocado met limoen.","Serveer als bowl."));
        recipes.add(new Recipe("keto-beef","Keto gehaktbowl met bloemkoolrijst","🥩","Anders",25,"Rundergehakt met bloemkoolrijst, paprika en avocado.","keto lowcarb beef protein vegetables",
                L(I("mager rundergehakt","160 g p.p.",false),I("bloemkoolrijst","220 g p.p.",false),I("paprika","120 g p.p.",false),I("avocado","¼ p.p.",false),I("komijn","1 tl",true),I("olijfolie","1 el",true)),
                "Bak het gehakt met komijn.","Bak paprika mee.","Roerbak bloemkoolrijst kort.","Serveer met avocado."));
        recipes.add(new Recipe("keto-egg","Frittata met spinazie, champignons en feta","🍳","Anders",30,"Eiwitrijke frittata met groente en feta.","keto lowcarb veg egg protein calcium vegetables menopause bone",
                L(I("eieren","3 p.p.",false),I("spinazie","120 g p.p.",false),I("champignons","100 g p.p.",false),I("feta","35 g p.p.",false),I("olijfolie","1 el",true)),
                "Bak champignons en spinazie.","Klop eieren los.","Giet erbij en verdeel feta erover.","Laat rustig garen of zet kort onder de grill."));
        recipes.add(new Recipe("fodmap-salmon","Low-FODMAP zalm met rijst en wortel-courgette","🐟","Rijst",25,"Eenvoudige variant met ingrediënten die vaak binnen een low-FODMAP aanpak passen.","lowfodmap fish salmon protein vegetables omega3",
                L(I("zalm","150 g p.p.",false),I("witte rijst","75 g p.p.",false),I("wortel","100 g p.p.",false),I("courgette","100 g p.p.",false),I("citroen","½",false),I("knoflookolie","1 el",false)),
                "Kook de rijst.","Bak zalm in knoflookolie.","Roerbak wortel en courgette.","Serveer met citroen."));
        recipes.add(new Recipe("fodmap-chicken","Low-FODMAP kip met aardappel en sperziebonen","🍗","AVG",30,"Milde kipmaaltijd zonder ui of gewone knoflook.","lowfodmap chicken protein vegetables child",
                L(I("kipfilet","150 g p.p.",false),I("aardappelen","220 g p.p.",false),I("sperziebonen","180 g p.p.",false),I("bieslook","1 el",false),I("knoflookolie","1 el",false)),
                "Kook aardappelen en sperziebonen.","Bak kip in knoflookolie.","Breng op smaak met bieslook.","Serveer samen."));
        recipes.add(new Recipe("fodmap-pasta","Low-FODMAP glutenvrije pasta met tomaat en spinazie","🍝","Pasta",25,"Pasta zonder ui/knoflook met tomaat, spinazie en basilicum.","lowfodmap veg vegetables",
                L(I("glutenvrije pasta","90 g p.p.",false),I("passata zonder ui/knoflook","150 ml p.p.",false),I("spinazie","140 g p.p.",false),I("Parmezaanse kaas","15 g p.p.",false),I("basilicum","1 tl",true),I("knoflookolie","1 el",false)),
                "Kook de pasta.","Warm passata met spinazie.","Meng met pasta en basilicum.","Werk af met Parmezaan."));
        recipes.add(new Recipe("dash-chicken","DASH kip met zoete aardappel en groene groente","🍠","AVG",35,"Veel groente, magere kip en smaak uit kruiden in plaats van veel zout.","dash bp heart glucose protein vegetables chicken child",
                L(I("kipfilet","150 g p.p.",false),I("zoete aardappel","200 g p.p.",false),I("broccoli","180 g p.p.",false),I("paprikapoeder","1 tl",true),I("citroen","½",false),I("olijfolie","1 el",true)),
                "Rooster zoete aardappel en broccoli.","Kruid kip met paprikapoeder en bak gaar.","Besprenkel met citroen.","Serveer zonder extra zoute saus."));
        recipes.add(new Recipe("mind-bowl","MIND bowl met kip, spinazie, bessen en walnoten","🥗","Anders",20,"Bowl met bladgroente, bessen, walnoten en volkoren graan.","mind heart vegetables protein chicken wholegrain",
                L(I("kipfilet","120 g p.p.",false),I("spinazie","100 g p.p.",false),I("blauwe bessen","60 g p.p.",false),I("walnoten","20 g p.p.",false),I("volkoren couscous","60 g p.p.",false),I("olijfolie","1 el",true),I("citroen","½",false)),
                "Bereid couscous.","Bak kipfilet.","Meng spinazie, bessen en walnoten.","Voeg couscous, kip, olijfolie en citroen toe."));
    }
}
