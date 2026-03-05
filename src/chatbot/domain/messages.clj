(ns chatbot.domain.messages
  "Централизованное хранилище всех текстовых сообщений и кнопок бота.

   Этот файл позволяет быстро редактировать тексты сообщений,
   которые применяются одинаково для Telegram и MAX.

   Структура:
   - texts - текстовые сообщения
   - buttons - тексты кнопок
   - keyboards - готовые клавиатуры")

;; ============================================
;; ТЕКСТЫ КНОПОК
;; ============================================

(def buttons
  "Тексты для всех кнопок бота"
  {;; Главное меню
   :menu-support     "📞 Техподдержка"
   :menu-review      "⭐ Оставить отзыв"
   :menu-promo       "🎁 Получить промокод"
   :menu-my-promos   "🗂 Мои промокоды"
   :menu-email       "📧 Оставить email"

   ;; Общие действия
   :cancel           "❌ Отмена"
   :back-to-menu     "🔙 В меню"
   :submit-review    "📤 Отправить отзыв"
   :confirm-review   "✅ Подтвердить"
   :edit-review      "✏️ Изменить"
   :check-subscription "✅ Проверить подписку"
   :subscribe-channel  "📢 Подписаться на канал"

   ;; Поддержка (чат)
   :end-support      "🔚 Завершить диалог"

   ;; Админ-панель
   :admin-campaigns  "📣 Кампании"
   :admin-reviews    "📋 Отзывы на модерации"
   :admin-stats      "📊 Статистика"
   :admin-back-user  "🔙 В меню пользователя"
   :admin-back        "🔙 Назад"
   :admin-menu        "🔙 Админ меню"

   ;; Модерация отзывов
   :show-photos      "🖼 Показать фото"
   :approve          "✅ Одобрить"
   :reject           "❌ Отклонить"
   :skip-review      "⏭️ Пропустить"

   ;; Черновик отзыва
   :delete-photos    "🗑 Удалить фото"
   :delete-text      "🗑 Удалить текст"
   :preview-review   "👁 Предпросмотр"

   ;; Кампании
   :admin-campaign-new    "➕ Новая кампания"
   :campaign-skip         "⏭ Пропустить"
   :campaign-confirm      "✅ Создать кампанию"
   :campaign-keep-current  "⏭ Оставить текущее"
   :campaign-save          "✅ Сохранить"})

;; ============================================
;; ТЕКСТОВЫЕ СООБЩЕНИЯ
;; ============================================

(def texts
  "Все текстовые сообщения бота"
  {;; Приветствие и меню
   :welcome
   "👋 Добро пожаловать!\n\nВыберите действие:"

   :select-action
   "Выберите действие из меню:"

   :action-cancelled
   "❌ Действие отменено. Возвращаемся в главное меню..."

   :cancelled
   "❌ Отменено."

   :unknown-command
   "Неизвестная команда. Используйте /start"

   :unknown-action
   "Неизвестное действие."

   :technical-error
   "⚠️ Произошла техническая ошибка. Попробуйте позже."

   ;; Техподдержка
   :support-start
   "📞 *Техподдержка*\n\nОпишите вашу проблему, и мы передадим её оператору.\n\nДля отмены нажмите кнопку ниже или отправьте /cancel"

   :support-received
   "✅ Ваше обращение принято!\n\nОператор скоро ответит вам в этом чате.\n\nДля завершения диалога отправьте /cancel"

   :support-forwarded
   "📨 Сообщение передано оператору."

   :support-agent-message
   "💬 *Ответ оператора:*\n\n%s"

   :support-session-closed
   "✅ Обращение закрыто оператором. Если есть ещё вопросы — нажмите \"Техподдержка\"."

   :support-crm-unavailable
   "⚠️ Служба поддержки временно недоступна. Обращение сохранено."

   ;; Отзывы
   :review-start
   "⭐ *Оставить отзыв*\n\nОтправьте фото и текст вашего отзыва.\n\nКогда закончите, нажмите \"Отправить отзыв\"."

   :review-photo-added
   "📷 Фото добавлено (%d шт.)%s\n\nОтправьте ещё фото, текст отзыва, или нажмите \"Отправить\"."

   :review-text-with-caption
   "\n✏️ Текст описания сохранен."

   :review-text-added
   "✏️ Текст добавлен. Отправьте фото или нажмите \"Отправить\"."

   :review-text-replaced
   "✏️ Текст заменён. Отправьте фото или нажмите \"Отправить\"."

   :review-confirm-prompt
   "Нажмите «Подтвердить» для отправки или «Изменить» для редактирования."

   :review-send-content
   "Отправьте фото или текст отзыва."

   :review-submitted
   "✅ Ваш отзыв отправлен на модерацию!\n\nМы уведомим вас о результате."

   :review-approved
   "✅ Ваш отзыв одобрен и опубликован! Спасибо!"

   :review-published
   "⭐️ *Новый отзыв!*\n\n%s\n\n#отзывы"

   :review-rejected
   "❌ К сожалению, ваш отзыв не прошёл модерацию."

   ;; Промокоды
   :promo-in-development
   "🎁 *Промокод*\n\nФункция в разработке.\n\nДля получения промокода необходимо подписаться на наш канал."

   :promo-subscribe-required
   "🎁 *Получить промокод*\n\nДля получения промокода подпишитесь на наш канал и нажмите \"Проверить подписку\"."

   :promo-not-subscribed
   "❌ Вы ещё не подписаны на канал.\n\nПодпишитесь и нажмите \"Проверить подписку\"."

   :promo-code-issued
   "🎉 *Ваш промокод:*\n\n`%s`\n\nСкопируйте и используйте при заказе!"

   :promo-already-received
   "ℹ️ Вы уже получили промокод: `%s`"

   :promo-no-codes-available
   "😔 К сожалению, промокоды закончились.\n\nПопробуйте позже!"

   :promo-unavailable
   "Промо-акция временно недоступна."

   :promo-already-has
   "🎁 Вы уже получили промокод!\n\nВаш код: *%s*"

   :promo-need-subscribe
   "❌ Для получения промокода необходимо подписаться на наш канал.\n\nПосле подписки нажмите кнопку \"Проверить подписку\"."

   :promo-issued
   "🎉 Поздравляем!\n\nВаш промокод: *%s*\n\nИспользуйте его при следующем заказе!"

   :promo-all-out
   "😔 К сожалению, все промокоды закончились.\n\nСледите за обновлениями!"

   :promo-no-active-campaign
   "🎁 Сейчас активных акций нет.\n\nПодпишитесь на наш канал, чтобы не пропустить следующую!"

   :welcome-promo
   "🎁 Для вас — приветственный промокод!\nПодпишитесь на наш канал и получите скидку на первый заказ."

   ;; Email промокод
   :email-prompt
   "📧 *Оставить email*\n\nВведите ваш email-адрес и получите промокод на скидку:"

   :email-invalid
   "⚠️ Некорректный email-адрес. Попробуйте ещё раз или нажмите «Отмена»."

   :email-already-have
   "ℹ️ Ваш email уже сохранён.\n\nВаш промокод: *%s*"

   :email-saved-no-promo
   "✅ Email сохранён!\n\nАктивных акций сейчас нет. Следите за обновлениями!"

   ;; История промокодов
   :my-promos-title "📋 *Ваши промокоды:*\n\n%s"
   :my-promos-empty "У вас пока нет промокодов.\n\nПолучите первый — подпишитесь на наш канал!"
   :promo-history-item "• `%s` — *%s*\n  %s"
   :promo-already-issued-redirect "Вы уже получили промокод в этой акции. Вот ваши промокоды:"

   ;; Админ-панель
   :admin-menu-title
   "👨‍💼 *Панель администратора*\n\nВыберите действие:"

   :admin-reviews-empty
   "📋 *Отзывы на модерации*\n\n✅ Нет отзывов, ожидающих модерации."

   :admin-review-item
   "📋 *Отзыв на модерации*\n\nОт: %s\nДата: %s\n\n%s\n\n📷 Фото: %d шт."

   :admin-stats-title
   "📊 *Статистика*\n\n👥 Всего пользователей: %d\n🎁 Выдано промокодов: %d\n⭐ Отзывов: %d"

   ;; Кампании - мастер создания
   :admin-campaign-enter-name
   "✏️ Введите название кампании:"

   :admin-campaign-enter-code
   "🎁 Введите промокод:"

   :admin-campaign-enter-description
   "📝 Введите описание условий промокода для администраторов\n(например: «действует на один заказ от 3000 руб.»)\nили нажмите «Пропустить»:"

   :admin-campaign-enter-expires
   "📅 Введите дату окончания акции в формате ГГГГ-ММ-ДД\n(например, 2026-03-01) или нажмите «Пропустить»:"

   :admin-campaign-invalid-date
   "⚠️ Неверный формат даты. Используйте: ГГГГ-ММ-ДД (например, 2026-03-01)"

   ;; Рассылка уведомлений
   :broadcast-notification
   "🎁 Новая акция — *%s*!\n\nНажмите кнопку ниже, чтобы получить ваш промокод."

   :broadcast-confirm-prompt
   "📣 Будет отправлено *%d* пользователям.\n\nКампания: *%s*\n\nПродолжить?"

   :broadcast-started
   "📣 Рассылка запущена! Уведомления отправляются в фоновом режиме."

   :broadcast-no-targets
   "ℹ️ Нет пользователей для рассылки."})

;; ============================================
;; ГОТОВЫЕ КЛАВИАТУРЫ
;; ============================================

(defn main-menu-keyboard
  "Клавиатура главного меню (reply — постоянная панель внизу, кнопки в столбец)"
  []
  {:type :reply
   :buttons [[{:text (:menu-support buttons) :callback "menu_support"}]
             [{:text (:menu-review buttons)  :callback "menu_review"}]
             [{:text (:menu-promo buttons)   :callback "menu_promo"}]
             [{:text (:menu-my-promos buttons) :callback "my_promos"}]
             [{:text (:menu-email buttons)   :callback "menu_email"}]]})

(defn cancel-keyboard
  "Клавиатура с кнопкой отмены (reply)"
  []
  {:type :reply
   :buttons [[{:text (:cancel buttons) :callback "cancel"}]]})

(defn review-keyboard
  "Клавиатура для отзыва (reply — всегда внизу, не уплывает вверх)"
  []
  {:type :reply
   :buttons [[{:text (:submit-review buttons) :callback "submit_review"}]
             [{:text (:cancel buttons)        :callback "cancel"}]]})

(defn review-confirm-keyboard
  "Клавиатура подтверждения отправки отзыва (reply)"
  []
  {:type :reply
   :buttons [[{:text (:confirm-review buttons) :callback "confirm_review"}]
             [{:text (:edit-review buttons)    :callback "edit_review"}]]})

(defn support-chat-keyboard
  "Клавиатура для активного чата с поддержкой (reply)"
  []
  {:type :reply
   :buttons [[{:text (:end-support buttons) :callback "end_support"}]]})

(defn back-to-menu-keyboard
  "Клавиатура с кнопкой возврата в меню"
  []
  {:type :inline
   :buttons [[{:text (:back-to-menu buttons) :callback "back_to_menu"}]]})

(defn promo-subscribe-keyboard
  "Клавиатура подписки на канал.
   Если channel-link nil, кнопка подписки не показывается."
  [channel-link]
  {:type :inline
   :buttons (cond-> []
              ;; Show subscribe button only if link is available
              channel-link (conj [{:text (:subscribe-channel buttons) :url channel-link}])
              ;; Always show check and back buttons
              true (conj [{:text (:check-subscription buttons) :callback "check_subscription"}])
              true (conj [{:text (:back-to-menu buttons) :callback "back_to_menu"}]))})

(defn promo-check-keyboard
  "Клавиатура проверки подписки"
  []
  {:type :inline
   :buttons [[{:text (:check-subscription buttons) :callback "check_subscription"}]
             [{:text (:back-to-menu buttons) :callback "back_to_menu"}]]})

(defn my-promos-keyboard
  "Клавиатура для экрана 'Мои промокоды' (inline)"
  []
  {:type :inline
   :buttons [[{:text "🎁 Получить промокод" :callback "menu_promo"}]
             [{:text "🔙 В меню" :callback "back_to_menu"}]]})

(defn email-ask-keyboard
  "Клавиатура при запросе email: только кнопка отмены (reply)"
  []
  {:type :reply
   :buttons [[{:text (:cancel buttons) :callback "cancel"}]]})

(defn welcome-promo-keyboard
  "Inline-клавиатура для приветственного промокода нового пользователя"
  []
  {:type :inline
   :buttons [[{:text "🎁 Получить промокод" :callback "menu_promo"}]]})

(defn broadcast-notify-keyboard
  "Клавиатура для уведомления о рассылке (inline): кнопка получения промокода + в меню"
  []
  {:type :inline
   :buttons [[{:text "🎁 Получить промокод" :callback "menu_promo"}]
             [{:text "🔙 В меню" :callback "back_to_menu"}]]})

(defn admin-menu-keyboard
  "Клавиатура админ-меню (reply — постоянная панель внизу)"
  []
  {:type :reply
   :buttons [[{:text (:admin-campaigns buttons) :callback "admin_campaigns"}]
             [{:text (:admin-reviews buttons)    :callback "admin_reviews"}]
             [{:text (:admin-stats buttons)      :callback "admin_stats"}]
             [{:text (:admin-back-user buttons)  :callback "back_to_menu"}]]})

(defn admin-cancel-keyboard
  "Клавиатура отмены в админке"
  [back-callback]
  {:type :inline
   :buttons [[{:text (:cancel buttons) :callback back-callback}]]})

(defn admin-back-keyboard
  "Клавиатура возврата в админ-меню (reply)"
  []
  {:type :reply
   :buttons [[{:text (:admin-menu buttons) :callback "admin_menu"}]]})

(defn moderation-keyboard
  "Клавиатура модерации отзыва (одобрить/отклонить)"
  [review-id]
  {:type :inline
   :buttons [[{:text (:approve buttons) :callback (str "approve_" review-id)}
              {:text (:reject buttons) :callback (str "reject_" review-id)}]]})

(defn format-draft-text
  "Форматирует текст панели черновика с текущим состоянием отзыва."
  [state-data]
  (let [photos     (:photos state-data)
        text       (:text state-data)
        photo-line (if (seq photos)
                     (str "📷 Фото: " (count photos) " шт.")
                     "📷 Фото: нет")
        text-line  (if text
                     (str "💬 Текст:\n" text)
                     "💬 Текст: нет")]
    (str "✏️ *Редактирование отзыва*\n\n"
         photo-line "\n"
         text-line "\n\n"
         "_Отправьте фото или текст, чтобы добавить их к отзыву._")))

(defn draft-panel-keyboard
  "Клавиатура панели редактирования черновика (inline, кнопки зависят от состояния)."
  [state-data]
  (let [photos (:photos state-data)
        text   (:text state-data)]
    {:type :inline
     :buttons (cond-> []
                (seq photos) (conj [{:text (:delete-photos buttons) :callback "delete_photos"}])
                text         (conj [{:text (:delete-text buttons)   :callback "delete_text"}])
                true         (conj [{:text (:preview-review buttons) :callback "submit_review"}])
                true         (conj [{:text (:cancel buttons)         :callback "cancel"}]))}))

(defn admin-campaigns-keyboard
  "Клавиатура списка кампаний — кнопка переключения + уведомление/изменить/удалить для каждой + создать/назад."
  [campaigns]
  {:type :inline
   :buttons (vec (concat
                   (mapcat (fn [c]
                             (let [cid        (:id c)
                                   toggle-btn [{:text     (str (if (:is-active c) "⏸ " "▶️ ") (:name c))
                                                :callback (str "toggle_campaign_" cid)}]
                                   mgmt-row   (cond-> []
                                                (:is-active c) (conj {:text "📣 Уведомить" :callback (str "notify_campaign_" cid)})
                                                true           (conj {:text "✏️ Изменить"  :callback (str "edit_campaign_" cid)})
                                                true           (conj {:text "🗑 Удалить"   :callback (str "delete_campaign_" cid)}))]
                               [toggle-btn mgmt-row]))
                           campaigns)
                   [[{:text (:admin-campaign-new buttons) :callback "admin_campaign_new"}]]
                   [[{:text (:admin-back         buttons) :callback "admin_menu"}]]))})

(defn admin-campaign-step-keyboard
  "Клавиатура для шага мастера: опциональная кнопка «Пропустить» + «Отмена»."
  [skip-callback]
  {:type :inline
   :buttons (cond-> []
              skip-callback (conj [{:text (:campaign-skip buttons) :callback skip-callback}])
              true          (conj [{:text (:cancel         buttons) :callback "admin_campaigns"}]))})

(defn admin-campaign-confirm-keyboard
  "Клавиатура финального подтверждения создания кампании."
  []
  {:type :inline
   :buttons [[{:text (:campaign-confirm buttons) :callback "campaign_confirm"}]
             [{:text (:cancel           buttons) :callback "admin_campaigns"}]]})

(defn admin-campaign-edit-step-keyboard
  "Клавиатура для шага редактирования: кнопка «Оставить текущее» + «Отмена»."
  [keep-callback]
  {:type :inline
   :buttons [[{:text (:campaign-keep-current buttons) :callback keep-callback}]
             [{:text (:cancel                buttons) :callback "admin_campaigns"}]]})

(defn admin-campaign-edit-confirm-keyboard
  "Клавиатура финального подтверждения редактирования кампании."
  []
  {:type :inline
   :buttons [[{:text (:campaign-save buttons) :callback "campaign_edit_save"}]
             [{:text (:cancel        buttons) :callback "admin_campaigns"}]]})

;; ============================================
;; REPLY KEYBOARD NORMALIZATION
;; ============================================

(def reply-button-callbacks
  "Маппинг текста reply-кнопки → callback-data.
   Позволяет обрабатывать нажатия reply-кнопок так же, как inline callbacks."
  {;; Главное меню
   (:menu-support buttons)      "menu_support"
   (:menu-review buttons)       "menu_review"
   (:menu-promo buttons)        "menu_promo"
   (:menu-my-promos buttons)    "my_promos"
   ;; Отзыв
   (:cancel buttons)            "cancel"
   (:submit-review buttons)     "submit_review"
   (:confirm-review buttons)    "confirm_review"
   (:edit-review buttons)       "edit_review"
   ;; Поддержка
   (:end-support buttons)       "end_support"
   ;; Админ-меню
   (:admin-campaigns buttons)    "admin_campaigns"
   (:admin-reviews buttons)     "admin_reviews"
   (:admin-stats buttons)       "admin_stats"
   (:admin-back-user buttons)   "back_to_menu"
   (:admin-back buttons)         "admin_menu"
   (:admin-menu buttons)         "admin_menu"
   ;; Email promo
   (:menu-email buttons)         "menu_email"})

;; ============================================
;; ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ
;; ============================================

(defn t
  "Получить текст по ключу. Поддерживает форматирование.

   Примеры:
   (t :welcome)
   (t :promo-code-issued \"ABC123\")
   (t :review-photo-added 3 \"\")"
  [key & args]
  (let [template (get texts key (str "Missing text: " (name key)))]
    (if (seq args)
      (apply format template args)
      template)))

(defn b
  "Получить текст кнопки по ключу"
  [key]
  (get buttons key (str "?" (name key))))
