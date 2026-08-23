package nl.weekplanner.ah;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.*;

public class HouseholdManager {
    public static final int BREAKFAST = 0;
    public static final int LUNCH = 1;
    public static final int DINNER = 2;

    public static class Person {
        public String name;
        public String type;
        public int age;
        public boolean menopause;
        public boolean skin;
        public boolean heart;
        public boolean glucose;
        public boolean bloodPressure;
        public boolean bone;
        public boolean muscle;
        public boolean[][] schedule = new boolean[14][3];

        public Person(String name, String type, int age) {
            this.name = name;
            this.type = type;
            this.age = age;
        }

        public String healthSummary() {
            List<String> p = new ArrayList<>();
            if (menopause) p.add("overgang");
            if (skin) p.add("huid/psoriasis");
            if (heart) p.add("hart & vaten");
            if (glucose) p.add("glucose");
            if (bloodPressure) p.add("bloeddruk");
            if (bone) p.add("botgezondheid");
            if (muscle) p.add("spiermassa/eiwit");
            return p.isEmpty() ? "geen extra aandachtspunten" : android.text.TextUtils.join(" • ", p);
        }
    }

    private final SharedPreferences prefs;
    private final List<Person> people = new ArrayList<>();

    public HouseholdManager(SharedPreferences prefs) {
        this.prefs = prefs;
        load();
    }

    public List<Person> people() { return people; }

    public int count(LocalDate date, int meal) {
        int idx = scheduleIndex(date);
        int n = 0;
        for (Person p : people) if (p.schedule[idx][meal]) n++;
        return n;
    }

    public int totalMealPortions(LocalDate monday, int meal) {
        int total = 0;
        for (int d=0; d<7; d++) total += count(monday.plusDays(d), meal);
        return total;
    }

    public String profileSummary() {
        int adults=0, children=0;
        boolean menopause=false, skin=false, heart=false, glucose=false, bp=false, bone=false, muscle=false;
        for(Person p:people){
            if("Kind".equals(p.type)) children++; else adults++;
            menopause |= p.menopause; skin |= p.skin; heart |= p.heart; glucose |= p.glucose;
            bp |= p.bloodPressure; bone |= p.bone; muscle |= p.muscle;
        }
        List<String> s=new ArrayList<>();
        if(adults>0) s.add(adults+" volw.");
        if(children>0) s.add(children+" kind"+(children==1?"":"eren"));
        if(menopause) s.add("overgang"); if(skin) s.add("huid"); if(heart) s.add("hart");
        if(glucose) s.add("glucose"); if(bp) s.add("bloeddruk"); if(bone) s.add("bot"); if(muscle) s.add("eiwit");
        return android.text.TextUtils.join(" • ", s);
    }

    public Set<String> nutritionFlags() {
        Set<String> f = new LinkedHashSet<>();
        for(Person p: people) {
            if(p.menopause) f.add("overgang");
            if(p.skin) f.add("huid/psoriasis");
            if(p.heart) f.add("hart & vaten");
            if(p.glucose) f.add("glucose");
            if(p.bloodPressure) f.add("bloeddruk");
            if(p.bone) f.add("botgezondheid");
            if(p.muscle) f.add("spiermassa/eiwit");
            if("Kind".equals(p.type)) f.add("kindvriendelijk");
        }
        return f;
    }

    private int scheduleIndex(LocalDate date) {
        int week = date.get(WeekFields.ISO.weekOfWeekBasedYear());
        int base = (week % 2 == 0) ? 7 : 0;
        return base + date.getDayOfWeek().getValue() - 1;
    }

    public void save() {
        try {
            JSONArray arr = new JSONArray();
            for(Person p:people){
                JSONObject o=new JSONObject();
                o.put("name",p.name);o.put("type",p.type);o.put("age",p.age);
                o.put("menopause",p.menopause);o.put("skin",p.skin);o.put("heart",p.heart);o.put("glucose",p.glucose);
                o.put("bloodPressure",p.bloodPressure);o.put("bone",p.bone);o.put("muscle",p.muscle);
                JSONArray sch=new JSONArray();
                for(int d=0;d<14;d++){
                    JSONArray m=new JSONArray();
                    for(int x=0;x<3;x++)m.put(p.schedule[d][x]);
                    sch.put(m);
                }
                o.put("schedule",sch); arr.put(o);
            }
            prefs.edit().putString("household_v2",arr.toString()).apply();
        } catch(Exception ignored) {}
    }

    private void load() {
        people.clear();
        String raw=prefs.getString("household_v2","");
        if(!raw.isEmpty()){
            try{
                JSONArray arr=new JSONArray(raw);
                for(int i=0;i<arr.length();i++){
                    JSONObject o=arr.getJSONObject(i);
                    Person p=new Person(o.optString("name","Persoon"),o.optString("type","Man"),o.optInt("age",40));
                    p.menopause=o.optBoolean("menopause");p.skin=o.optBoolean("skin");p.heart=o.optBoolean("heart");p.glucose=o.optBoolean("glucose");
                    p.bloodPressure=o.optBoolean("bloodPressure");p.bone=o.optBoolean("bone");p.muscle=o.optBoolean("muscle");
                    JSONArray sch=o.optJSONArray("schedule");
                    if(sch!=null){for(int d=0;d<Math.min(14,sch.length());d++){JSONArray m=sch.optJSONArray(d);if(m!=null)for(int x=0;x<Math.min(3,m.length());x++)p.schedule[d][x]=m.optBoolean(x);}}
                    people.add(p);
                }
                if(!people.isEmpty()) return;
            }catch(Exception ignored){}
        }
        createDefaults();
        save();
    }

    private void createDefaults(){
        Person a=new Person("Ik","Man",43); a.skin=true;
        Person b=new Person("Partner","Vrouw",46); b.menopause=true; b.bone=true; b.muscle=true;
        Person c=new Person("Dochter","Kind",12);
        for(int d=0;d<14;d++){
            int dow=d%7;
            for(Person p:Arrays.asList(a,b)){
                p.schedule[d][BREAKFAST]=true;
                p.schedule[d][LUNCH]=(dow>=4);
                p.schedule[d][DINNER]=true;
            }
        }
        for(int d=9;d<14;d++){
            c.schedule[d][BREAKFAST]=true;c.schedule[d][LUNCH]=true;c.schedule[d][DINNER]=true;
        }
        people.add(a);people.add(b);people.add(c);
    }
}
