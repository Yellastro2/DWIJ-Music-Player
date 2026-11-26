# Каталог треков артиста в Яндекс Музыке

- `GET /artists/{id}/brief-info` отдаёт профиль артиста и подборку `popularTracks`.
- `GET /artists/{id}/tracks?page=0&page-size=20` отдаёт страницы треков каталога. Номер
  страницы начинается с нуля. У ответа есть `tracks` и может быть `pager` с полями
  `page`, `perPage`, `total`. Формат сверили по исходникам стороннего клиента
  MarshalX. Живой ответ с авторизацией DWIJ пока не проверяли.
- DWIJ оставляет популярные треки в начале. Порядок остальных берёт из ответа и
  пропускает уже показанные ID Яндекс Музыки. Конец определяет по `pager` или
  пустой странице, поэтому страница из одних дублей не обрывает список.

[Запросы каталога](https://github.com/MarshalX/yandex-music-api/blob/main/yandex_music/_client/artists.py) ·
[Модель страницы](https://github.com/MarshalX/yandex-music-api/blob/main/yandex_music/artist/artist_tracks.py) ·
[Пагинатор](https://github.com/MarshalX/yandex-music-api/blob/main/yandex_music/pager.py)
