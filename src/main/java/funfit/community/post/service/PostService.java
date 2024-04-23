package funfit.community.post.service;

import funfit.community.exception.ErrorCode;
import funfit.community.exception.customException.BusinessException;
import funfit.community.post.dto.CreatePostRequest;
import funfit.community.post.dto.CreatePostResponse;
import funfit.community.post.dto.ReadPostListResponse;
import funfit.community.post.dto.ReadPostResponse;
import funfit.community.post.entity.Bookmark;
import funfit.community.post.entity.Category;
import funfit.community.post.entity.Post;
import funfit.community.post.repository.BookmarkRepository;
import funfit.community.post.repository.PostRepository;
import funfit.community.user.entity.User;
import funfit.community.user.repository.UserRepository;
import funfit.community.utils.JwtUtils;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
@Transactional
public class PostService {

    private final PostRepository postRepository;
    private final JwtUtils jwtUtils;
    private final UserRepository userRepository;
    private final BookmarkRepository bookmarkRepository;
    private final RedisTemplate<String, String> redisTemplateToString;
    private final RedisTemplate<String, ReadPostListResponse> redisTemplateToDto;
    private String currentTime;
    private String previousTime;
    private static final String BEST_POSTS = "best_posts";

    public PostService(PostRepository postRepository, JwtUtils jwtUtils, UserRepository userRepository, BookmarkRepository bookmarkRepository, RedisTemplate<String, String> redisTemplateToString, RedisTemplate<String, ReadPostListResponse> redisTemplateToDto) {
        this.postRepository = postRepository;
        this.jwtUtils = jwtUtils;
        this.userRepository = userRepository;
        this.bookmarkRepository = bookmarkRepository;
        this.redisTemplateToString = redisTemplateToString;
        this.redisTemplateToDto = redisTemplateToDto;

        LocalDateTime now = LocalDateTime.now();
        this.currentTime = LocalDateTime.of(now.getYear(), now.getMonth(), now.getDayOfMonth(), now.getHour(), now.getMinute(), now.getSecond()).toString();
        this.currentTime = this.previousTime = currentTime;
    }

    public CreatePostResponse create(CreatePostRequest createPostRequest, HttpServletRequest request) {
        String email = jwtUtils.getEmailFromHeader(request);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_USER));
        Post post = Post.create(user, createPostRequest.getTitle(), createPostRequest.getContent(), Category.find(createPostRequest.getCategoryName()));
        postRepository.save(post);
        return new CreatePostResponse(user.getName(), post.getTitle(), post.getContent(), post.getCategory().getName(), post.getCreatedAt());
    }

    public ReadPostResponse readOne(long postId) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));

        post.increaseViews();
        reflectBestPostsInCache(postId);

        int bookmarkCount = bookmarkRepository.findByPost(post).size();
        return new ReadPostResponse(post.getUser().getName(), post.getTitle(), post.getContent(),
                post.getCategory().getName(), post.getCreatedAt(), post.getUpdatedAt(), bookmarkCount, post.getViews());
    }

    public void reflectBestPostsInCache(long postId) {
        ZSetOperations<String, String> zSetOperations = redisTemplateToString.opsForZSet();
        zSetOperations.incrementScore(currentTime, String.valueOf(postId), 1);
    }

    /**
     * 정각마다 수행되는 메서드
     * - previousTime, CurrentTime 값 갱신
     * - previousTime의 best posts 10개를 추출해서 캐시
     */
    @Scheduled(cron = "0 0 * * * *")
    public void extractBestPosts() {
        updateTime();

        // previousTime가 key인 캐시 데이터 조회 및 삭제
        ZSetOperations<String, String> zSetOperations = redisTemplateToString.opsForZSet();
        Set<String> bestPostIds = zSetOperations.reverseRange(previousTime, 0, 9);
        redisTemplateToString.delete(previousTime);

        // 조회한 best posts에 대한 정보를 DB에서 조회
        List<Post> bestPosts = bestPostIds.stream()
                .map(postId -> postRepository.findById(Long.valueOf(postId)).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND)))
                .toList();

        // best posts를 dto로 변환
        List<ReadPostListResponse.ReadPostResponseInList> list = bestPosts.stream()
                .map(post -> new ReadPostListResponse.ReadPostResponseInList(post.getUser().getName(), post.getTitle(), post.getCategory().getName(),
                        post.getCreatedAt(), post.getUpdatedAt(), bookmarkRepository.findByPost(post).size(), post.getViews()))
                .toList();
        ReadPostListResponse readPostListResponse = new ReadPostListResponse(list);

        // 기존의 best_posts 삭제 후 새로운 값 저장
        redisTemplateToDto.delete(BEST_POSTS);
        redisTemplateToDto.opsForList().rightPushAll(BEST_POSTS, readPostListResponse);
    }

    private void updateTime() {
        previousTime = currentTime;
        LocalDateTime now = LocalDateTime.now();
        currentTime = LocalDateTime.of(now.getYear(), now.getMonth(), now.getDayOfMonth(), now.getHour(), now.getMinute(), now.getSecond()).toString();
    }

    public ReadPostResponse bookmark(long postId, HttpServletRequest request) {
        Post post = postRepository.findById(postId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        String email = jwtUtils.getEmailFromHeader(request);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_USER));

        Optional<Bookmark> optionalBookmark = bookmarkRepository.findByPostAndUser(post, user);
        if (optionalBookmark.isPresent()) {
            Bookmark bookmark = optionalBookmark.get();
            bookmarkRepository.delete(bookmark);
        } else {
            bookmarkRepository.save(Bookmark.create(post, user));
        }

        int bookmarkCount = bookmarkRepository.findByPost(post).size();
        return new ReadPostResponse(post.getUser().getName(), post.getTitle(), post.getContent(),
                post.getCategory().getName(), post.getCreatedAt(), post. getUpdatedAt(), bookmarkCount, post.getViews());
    }

    public ReadPostListResponse readBestPosts() {
        return redisTemplateToDto.opsForList().leftPop(BEST_POSTS);
    }

    public Slice<ReadPostListResponse.ReadPostResponseInList> readPage(Pageable pageable) {
        Slice<Post> postsSlice = postRepository.findSliceBy(pageable);
        return postsSlice.map(post -> new ReadPostListResponse.ReadPostResponseInList(post.getUser().getName(), post.getTitle(),
                post.getCategory().getName(), post.getCreatedAt(), post.getUpdatedAt(),
                bookmarkRepository.findByPost(post).size(), post.getViews()));
    }
}
