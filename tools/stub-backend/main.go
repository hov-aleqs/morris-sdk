// Command stub-backend — заглушка рекламного бэкенда для разработки SDK.
//
// Отдаёт фиксированный ответ по контракту, чтобы клиентскую часть можно было
// писать и тестировать, не дожидаясь настоящего сервера. Никакой логики выбора
// спроса здесь нет и не будет: это инструмент разработки SDK, а не прототип
// бэкенда.
//
//	go run ./tools/stub-backend                 # :8080, обычный ответ
//	go run ./tools/stub-backend -scenario nofill
//
// Сценарии переключаются и на лету — заголовком X-Morris-Scenario, чтобы гонять
// разные ветки из одного билда приложения.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"strings"
	"time"
)

func main() {
	addr := flag.String("addr", ":8080", "адрес прослушивания")
	scenario := flag.String("scenario", "video", "video | nofill | broken | slow | noskip | nobranding")
	flag.Parse()

	mux := http.NewServeMux()
	mux.HandleFunc("/v1/ad", func(w http.ResponseWriter, r *http.Request) {
		sc := *scenario
		if h := r.Header.Get("X-Morris-Scenario"); h != "" {
			sc = h
		}

		body, _ := readBody(r)
		log.Printf("%s /v1/ad scenario=%s ua=%q body=%d байт", r.Method, sc, r.UserAgent(), len(body))

		w.Header().Set("Content-Type", "application/json; charset=utf-8")

		// Куда SDK будет слать пиксели: тот же адрес, по которому пришёл к нам.
		base := "http://" + r.Host

		switch sc {
		case "nofill":
			// Рекламы нет — это норма, а не ошибка. SDK обязан отличать.
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{}`))
		case "broken":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte(`{ это не json`))
		case "slow":
			time.Sleep(12 * time.Second)
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(mustJSON(videoAd(opts{base: base})))
		case "noskip":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(mustJSON(videoAd(opts{base: base})))
		case "skippable":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(mustJSON(videoAd(opts{base: base, SkipAfterMs: ptr(int64(5000))})))
		case "nobranding":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(mustJSON(videoAd(opts{base: base, NoBranding: true})))
		case "interstitial":
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(mustJSON(videoAd(opts{base: base, NoReward: true})))
		default:
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write(mustJSON(videoAd(opts{base: base})))
		}
	})

	// Приёмник пикселей: SDK стреляет сюда, а мы видим в логе порядок и тайминг.
	mux.HandleFunc("/t/", func(w http.ResponseWriter, r *http.Request) {
		log.Printf("ПИКСЕЛЬ %s", strings.TrimPrefix(r.URL.Path, "/t/"))
		w.WriteHeader(http.StatusOK)
	})

	log.Printf("заглушка слушает %s, сценарий по умолчанию %q", *addr, *scenario)
	log.Fatal(http.ListenAndServe(*addr, mux))
}

func readBody(r *http.Request) ([]byte, error) {
	defer func() { _ = r.Body.Close() }()
	buf := make([]byte, 0, 1024)
	tmp := make([]byte, 512)
	for {
		n, err := r.Body.Read(tmp)
		buf = append(buf, tmp[:n]...)
		if err != nil {
			return buf, nil
		}
		if len(buf) > 1<<20 {
			return buf, nil
		}
	}
}

type opts struct {
	SkipAfterMs *int64
	NoBranding  bool
	NoReward    bool

	// base — схема и адрес, по которым к нам обратились.
	base string
}

func ptr[T any](v T) *T { return &v }

// videoAd строит ответ той же формы, что описана в контракте. Набор media
// повторяет то, что реально приходит: четыре вертикальных варианта.
func videoAd(o opts) map[string]any {
	// Адрес для трекинговых ссылок берём из самого запроса, а не зашиваем:
	// заглушку зовут и с эмулятора, и с телефона через `adb reverse`, и с
	// соседней машины по сети. Зашитый адрес работал бы ровно в одном случае.
	base := o.base

	ad := map[string]any{
		"ad_id":         "stub-0001",
		"type":          "video",
		"duration_ms":   6000,
		"skip_after_ms": nil,
		"controls":      false,
		"media": []map[string]any{
			{"url": sample(1080), "w": 1080, "h": 1350, "bitrate": 1080, "mime": "video/mp4"},
			{"url": sample(852), "w": 852, "h": 1064, "bitrate": 1080, "mime": "video/mp4"},
			{"url": sample(640), "w": 640, "h": 800, "bitrate": 1080, "mime": "video/mp4"},
			{"url": sample(256), "w": 256, "h": 320, "bitrate": 1080, "mime": "video/mp4"},
		},
		"click": map[string]any{
			"url":   "https://example.com/landing",
			"label": "В магазин",
		},
		"tracking": map[string]any{
			"impression":      []string{base + "/t/impression"},
			"start":           []string{base + "/t/start"},
			"q1":              []string{base + "/t/q1"},
			"midpoint":        []string{base + "/t/midpoint"},
			"q3":              []string{base + "/t/q3"},
			"complete":        []string{base + "/t/complete"},
			"pause":           []string{base + "/t/pause"},
			"resume":          []string{base + "/t/resume"},
			"mute":            []string{base + "/t/mute"},
			"unmute":          []string{base + "/t/unmute"},
			"close":           []string{base + "/t/close"},
			"skip":            []string{base + "/t/skip"},
			"click":           []string{base + "/t/click"},
			"error":           []string{base + "/t/error"},
		},
		"ttl_ms": 1800000,
	}

	if o.SkipAfterMs != nil {
		ad["skip_after_ms"] = *o.SkipAfterMs
	}
	if !o.NoBranding {
		ad["branding"] = map[string]any{
			"erid": "2VtzqxAjfmM",
			"adchoices": map[string]any{
				"icon":  base + "/static/adchoices.png",
				"click": "https://example.com/about-ads",
				"close": base + "/t/adchoices_close",
			},
		}
	} else {
		ad["branding"] = map[string]any{}
	}
	if !o.NoReward {
		ad["reward"] = map[string]any{"amount": 1, "currency": "coins"}
	}
	return ad
}

// sample — публичный тестовый ролик. Для разработки достаточно, для приёмки
// нужен реальный креатив.
func sample(w int) string {
	return fmt.Sprintf(
		"https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4#w=%d", w)
}

func mustJSON(v any) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		log.Fatalf("не сериализовали ответ: %v", err)
	}
	return b
}
